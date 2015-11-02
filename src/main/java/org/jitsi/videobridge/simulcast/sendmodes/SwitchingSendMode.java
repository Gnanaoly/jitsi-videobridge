/*
 * Copyright @ 2015 Atlassian Pty Ltd
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.jitsi.videobridge.simulcast.sendmodes;

import net.java.sip.communicator.util.*;
import org.jitsi.impl.neomedia.*;
import org.jitsi.service.configuration.*;
import org.jitsi.util.Logger;
import org.jitsi.videobridge.*;
import org.jitsi.videobridge.simulcast.*;
import org.jitsi.videobridge.simulcast.messages.*;

import java.io.*;
import java.lang.ref.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.concurrent.locks.*;

/**
 * The <tt>SwitchingSendMode</tt> implements the switching layers mode in which
 * the endpoint receiving the simulcast that we send it is aware of all the
 * simulcast layer SSRCs and it manages the switching at the client side. The
 * receiving endpoint is notified about changes in the layers that it receives
 * through data channel messages.
 *
 * @author George Politis
 */
public class SwitchingSendMode
    extends SendMode
{
    /**
     * The <tt>Logger</tt> used by the <tt>ReceivingLayers</tt> class and its
     * instances to print debug information.
     */
    private static final Logger logger
            = Logger.getLogger(SwitchingSendMode.class);

    /**
     * Defines the default value of how many packets of the next layer must
     * be seen before switching to that layer. Also see <tt>minNextSeen</tt>.
     */
    private static int MIN_NEXT_SEEN_DEFAULT = 125;

    /**
     * The name of the property which can be used to control the
     * <tt>MIN_NEXT_SEEN</tt> constant.
     */
    private static final String MIN_NEXT_SEEN_PNAME =
        SwitchingSendMode.class.getName() + ".MIN_NEXT_SEEN";

    /**
     * Helper object that <tt>SwitchingSimulcastSender</tt> instances use to
     * build JSON messages.
     */
    private static final SimulcastMessagesMapper mapper
        = new SimulcastMessagesMapper();

    /**
     * A cyclic counters multitone that counts how many packets we've dropped
     * per SSRC.
     */
    private final CyclicCounters dropped = new CyclicCounters();

    /**
     * The sync root object protecting the access to the simulcast layers.
     */
    private final Object sendLayersSyncRoot = new Object();

    /**
     * Defines how many packets of the next layer must be seen before switching
     * to that layer. This value is appropriate for the base layer and needs to
     * be adjusted for use with upper layers, if one wants to achieve
     * (approximately) the same timeout for layers of different order.
     */
    private int minNextSeen = MIN_NEXT_SEEN_DEFAULT;

    /**
     * Holds the number of packets of the next layer have been seen so far.
     */
    private int seenNext;

    /**
     * A <tt>WeakReference</tt> to the <tt>SimulcastLayer</tt> that is
     * currently being received.
     */
    private WeakReference<SimulcastLayer> weakCurrent;

    /**
     * A <tt>WeakReference</tt> to the <tt>SimulcastLayer</tt> that will be
     * (possibly) received next.
     */
    private WeakReference<SimulcastLayer> weakNext;

    /**
     * A <tt>WeakReference</tt> to the <tt>SimulcastLayer</tt> that overrides
     * the layer that is currently being received. Originally introduced for
     * adaptive bitrate control and the <tt>SimulcastAdaptor</tt>.
     */
    private WeakReference<SimulcastLayer> weakOverride;

    /**
     * Boolean indicating whether this mode has been initialized or not.
     */
    private boolean isInitialized = false;

    /**
     * Ctor.
     *
     * @param simulcastSender
     */
    public SwitchingSendMode(SimulcastSender simulcastSender)
    {
        super(simulcastSender);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void receiveHigh()
    {
        SwitchingModeOptions options = new SwitchingModeOptions();

        options.setNextOrder(SimulcastLayer.SIMULCAST_LAYER_ORDER_HQ);
        options.setHardSwitch(true);
        // options.setUrgent(false);

        configure(options);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void receiveLow(boolean urgent)
    {
        SwitchingModeOptions options = new SwitchingModeOptions();

        options.setNextOrder(SimulcastLayer.SIMULCAST_LAYER_ORDER_LQ);
        options.setHardSwitch(true);
        options.setUrgent(urgent);

        configure(options);

        // Forget the next layer if it has stopped streaming.
        synchronized (sendLayersSyncRoot)
        {
            SimulcastLayer next = getNext();
            if (next != null && !next.isStreaming())
            {
                this.weakNext = null;
                this.seenNext = 0;

                nextSimulcastLayerStopped(next);
            }
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean accept(RawPacket pkt)
    {
        if (pkt == null)
        {
            return false;
        }

        this.assertInitialized();

        SimulcastLayer current = getCurrent();
        boolean accept = false;

        if (current != null)
            accept = current.match(pkt);

        if (!accept)
        {
            SimulcastLayer next = getNext();

            if (next != null)
            {
                accept = next.match(pkt);
                if (accept)
                    maybeSwitchToNext();
            }
        }

        SimulcastLayer override = getOverride();

        if (override != null)
            accept = override.match(pkt);

        if (!accept)
        {
            // For SRTP replay protection the webrtc.org implementation uses a
            // replay database with extended range, using a rollover counter
            // (ROC) which counts the number of times the RTP sequence number
            // carried in the RTP packet has rolled over.
            //
            // In this way, the ROC extends the 16-bit RTP sequence number to a
            // 48-bit "SRTP packet index". The ROC is not be explicitly
            // exchanged between the SRTP endpoints because in all practical
            // situations a rollover of the RTP sequence number can be detected
            // unless 2^15 consecutive RTP packets are lost.
            //
            // If this variable is set to true, then for every 0x800 (2048)
            // dropped packets (at most), we send 8 packets so that the
            // receiving endpoint can update its ROC.
            //
            // TODO(gp) We may want to move this code somewhere more centralized
            // to take into account last-n etc.

            Integer key = Integer.valueOf(pkt.getSSRC());
            CyclicCounter counter = dropped.getOrCreate(key, 0x800);
            accept = counter.cyclicallyIncrementAndGet() < 8;
        }

        if (logger.isDebugEnabled())
        {
            logger.debug("Accepting packet "
                + pkt.getSequenceNumber() + " from SSRC " + pkt.getSSRC());
        }

        return accept;
    }

    /**
     * Gets the <tt>SimulcastLayer</tt> that is currently being received.
     *
     * @return
     */
    private SimulcastLayer getCurrent()
    {
        WeakReference<SimulcastLayer> wr = this.weakCurrent;
        return (wr != null) ? wr.get() : null;
    }

    /**
     * Gets the <tt>SimulcastLayer</tt> that was previously being received.
     *
     * @return
     */
    private SimulcastLayer getNext()
    {
        WeakReference<SimulcastLayer> wr = this.weakNext;
        return (wr != null) ? wr.get() : null;
    }

    /**
     * Gets the <tt>SimulcastLayer</tt> that overrides the layer that is
     * currently being received. Originally introduced for the
     * <tt>SimulcastAdaptor</tt>.
     *
     * @return
     */
    private SimulcastLayer getOverride()
    {
        WeakReference<SimulcastLayer> wr = this.weakOverride;
        return (wr != null) ? wr.get() : null;
    }

    /**
     * Initializes this mode, if it has not already been initialized.
     */
    private void assertInitialized()
    {
        if (isInitialized)
        {
            return;
        }

        isInitialized = true;

        SwitchingModeOptions options = new SwitchingModeOptions();

        // or, stream both the current and the next stream simultaneously
        // to give some time to the client decoder to resume.
        VideoChannel sendVideoChannel = getSimulcastSender()
            .getSimulcastSenderManager().getSimulcastEngine().getVideoChannel();

        ConfigurationService cfg
            = ServiceUtils.getService(sendVideoChannel.getBundleContext(),
            ConfigurationService.class);

        options.setMinNextSeen(cfg != null
            ? cfg.getInt(SwitchingSendMode.MIN_NEXT_SEEN_PNAME,
                SwitchingSendMode.MIN_NEXT_SEEN_DEFAULT)
            : SwitchingSendMode.MIN_NEXT_SEEN_DEFAULT);

        this.configure(options);
    }

    /**
     * Sets the receiving simulcast substream for the peers in the endpoints
     * parameter.
     *
     * @param options
     */
    private void configure(SwitchingModeOptions options)
    {
        if (options == null)
        {
            logger.warn("cannot configure next simulcast layer because the " +
                "parameter is null.");
            return;
        }

        Integer mns = options.getMinNextSeen();
        if (mns != null)
        {
            this.minNextSeen = mns;
        }

        // Configures the "next" layer to receive, if one is to be configured.
        Integer nextOrder = options.getNextOrder();
        if (nextOrder == null)
        {
            return;
        }

        SimulcastReceiver simulcastReceiver
            = getSimulcastSender().getSimulcastReceiver();
        if (simulcastReceiver == null || !simulcastReceiver.hasLayers())
        {
            logger.warn("doesn't have any simulcast layers.");
            return;
        }

        SimulcastLayer next = simulcastReceiver == null
            ? null : simulcastReceiver.getSimulcastLayer(options.getNextOrder());

        // Do NOT switch to hq if it's not streaming.
        if (next == null
            || (next.getOrder()
            != SimulcastLayer.SIMULCAST_LAYER_ORDER_LQ
            && !next.isStreaming()))
        {
            return;
        }

        SimulcastLayer current = getCurrent();

        // Do NOT switch to an already receiving layer.
        if (current == next)
        {
            // and forget "previous" next, we're sticking with current.
            this.weakNext = null;
            this.seenNext = 0;

            return;
        }
        else
        {
            // If current has changed, request an FIR, notify the parent
            // endpoint and change the receiving streams.

            if (options.isHardSwitch() && next != getNext())
            {
                // XXX(gp) run these in the event dispatcher thread?

                // Send FIR requests first.
                if (getOverride() == null)
                {
                    next.askForKeyframe();
                }
                else
                {
                }
            }


            if (options.isUrgent() || current == null
                || this.minNextSeen < 1)
            {
                // Receiving simulcast layers have brutally changed. Create
                // and send an event through data channels to the receiving
                // endpoint.
                if (getOverride() == null)
                {
                    this.simulcastLayersChanged(next);
                }
                else
                {
                }

                this.weakCurrent = new WeakReference<SimulcastLayer>(next);
                this.weakNext = null;

                // Since the currently received layer has changed, reset the
                // seenCurrent counter.
                this.seenNext = 0;
            }
            else
            {
                // Receiving simulcast layers are changing, create and send
                // an event through data channels to the receiving endpoint.
                if (getOverride() == null)
                {
                    this.simulcastLayersChanging(next);
                }
                else
                {
                }

                // If the layer we receive has changed (hasn't dropped),
                // then continue streaming the previous layer for a short
                // period of time while the client receives adjusts its
                // video.
                this.weakNext = new WeakReference<SimulcastLayer>(next);

                // Since the currently received layer has changed, reset the
                // seenCurrent counter.
                this.seenNext = 0;
            }
        }
    }

    /**
     *
     */
    private void maybeSwitchToNext()
    {
        synchronized (sendLayersSyncRoot)
        {
            SimulcastLayer next = getNext();

            // If there is a previous layer to timeout, and we have received
            // "enough" packets from the current layer, expire the previous
            // layer.
            if (next != null)
            {
                this.seenNext++;

                // NOTE(gp) not unexpectedly we have observed that 250 high
                // quality packets make 5 seconds to arrive (approx), then 250
                // low quality packets will make 10 seconds to arrive (approx),
                // If we don't take that fact into account, then the immediate
                // lower layer makes twice as much to expire.
                //
                // Assuming that each upper layer doubles the number of packets
                // it sends in a given interval, we normalize the MAX_NEXT_SEEN
                // to reflect the different relative rates of incoming packets
                // of the different simulcast layers we receive.

                if (this.seenNext > this.minNextSeen * Math.pow(2, next.getOrder()))
                {
                    if (getOverride() == null)
                    {
                        this.simulcastLayersChanged(next);
                    }
                    else
                    {
                    }

                    this.weakCurrent = weakNext;
                    this.weakNext = null;
                }
            }
        }
    }

    /**
     *
     * @param layer
     */
    private void nextSimulcastLayerStopped(SimulcastLayer layer)
    {
        if (layer == null)
        {
            logger.warn("Requested to send a next simulcast layer stopped " +
                "event but layer is null!");
            return;
        }

        Endpoint self, peer;

        if ((self = getSimulcastSender().getReceiveEndpoint()) != null && (peer = getSimulcastSender().getSendEndpoint()) != null)
        {
            logger.debug("Sending a next simulcast layer stopped event to "
                + self.getID() + ".");

            // XXX(gp) it'd be nice if we could remove the
            // SimulcastLayersChangedEvent event. Ideally, receivers should
            // listen for MediaStreamTrackActivity instead. Unfortunately,
            // such an event does not exist in WebRTC.

            // Receiving simulcast layers changed, create and send
            // an event through data channels to the receiving endpoint.
            NextSimulcastLayerStoppedEvent ev
                = new NextSimulcastLayerStoppedEvent();

            ev.endpointSimulcastLayers = new EndpointSimulcastLayer[]{
                new EndpointSimulcastLayer(peer.getID(), layer)
            };

            String json = mapper.toJson(ev);
            try
            {
                // FIXME(gp) sendMessageOnDataChannel may silently fail to
                // send a data message. We want to be able to handle those
                // errors ourselves.
                self.sendMessageOnDataChannel(json);
            }
            catch (IOException e)
            {
                logger.error(self.getID() + " failed to send message on " +
                    "data channel.", e);
            }
        }
        else
        {
            logger.warn("Didn't send simulcast layers changed event " +
                "because self == null || peer == null " +
                "|| current == null");
        }
    }

    /**
     *
     * @param layer
     */
    private void simulcastLayersChanged(SimulcastLayer layer)
    {
        if (layer == null)
        {
            logger.warn("Requested to send a simulcast layers changed event" +
                    "but layer is null!");
            return;
        }

        Endpoint self, peer;

        if ((self = getSimulcastSender().getReceiveEndpoint()) != null && (peer = getSimulcastSender().getSendEndpoint()) != null)
        {
            logger.debug("Sending a simulcast layers changed event to "
                    + self.getID() + ".");

            // XXX(gp) it'd be nice if we could remove the
            // SimulcastLayersChangedEvent event. Ideally, receivers should
            // listen for MediaStreamTrackActivity instead. Unfortunately,
            // such an event does not exist in WebRTC.

            // Receiving simulcast layers changed, create and send
            // an event through data channels to the receiving endpoint.
            SimulcastLayersChangedEvent ev
                    = new SimulcastLayersChangedEvent();

            ev.endpointSimulcastLayers = new EndpointSimulcastLayer[]{
                    new EndpointSimulcastLayer(peer.getID(), layer)
            };

            String json = mapper.toJson(ev);
            try
            {
                // FIXME(gp) sendMessageOnDataChannel may silently fail to
                // send a data message. We want to be able to handle those
                // errors ourselves.
                self.sendMessageOnDataChannel(json);
            }
            catch (IOException e)
            {
                logger.error(self.getID() + " failed to send message on " +
                        "data channel.", e);
            }
        }
        else
        {
            logger.warn("Didn't send simulcast layers changed event " +
                    "because self == null || peer == null " +
                    "|| current == null");
        }

    }

    /**
     *
     * @param layer
     */
    private void simulcastLayersChanging(SimulcastLayer layer)
    {
        if (layer == null)
        {
            logger.warn("Requested to send a simulcast layers changing event" +
                    "but layer is null!");
            return;
        }

        Endpoint self
            = getSimulcastSender().getReceiveEndpoint();
        Endpoint peer = getSimulcastSender().getSendEndpoint();

        if (self != null && peer  != null)
        {
            logger.debug("Sending a simulcast layers changing event to "
                    + self.getID() + ".");

            // XXX(gp) it'd be nice if we could remove the
            // SimulcastLayersChangedEvent event. Ideally, receivers should
            // listen for MediaStreamTrackActivity instead. Unfortunately,
            // such an event does not exist in WebRTC.

            // Receiving simulcast layers changed, create and send
            // an event through data channels to the receiving
            // endpoint.
            SimulcastLayersChangingEvent ev
                    = new SimulcastLayersChangingEvent();

            ev.endpointSimulcastLayers = new EndpointSimulcastLayer[]{
                    new EndpointSimulcastLayer(peer.getID(), layer)
            };

            String json = mapper.toJson(ev);
            try
            {
                // FIXME(gp) sendMessageOnDataChannel may silently fail to
                // send a data message. We want to be able to handle those
                // errors ourselves.
                self.sendMessageOnDataChannel(json);
            }
            catch (IOException e)
            {
                logger.error(self.getID() + " failed to send message on " +
                        "data channel.", e);
            }
        }
        else
        {
            logger.warn("Didn't send simulcast layers changing event " +
                    "because self == null || peer == null " +
                    "|| current == null");
        }
    }

    /**
     *
     * @param options
     */
    private void maybeConfigureOverride(SwitchingModeOptions options)
    {
        if (options == null)
        {
            return;
        }

        Integer overrideOrder = 1; // options.getOverrideOrder();
        if (overrideOrder == null)
        {
            return;
        }

        SimulcastReceiver simulcastReceiver = this.getSimulcastSender().getSimulcastReceiver();

        if (simulcastReceiver == null || !simulcastReceiver.hasLayers())
        {
            return;
        }

        if (overrideOrder == SimulcastSenderManager.SIMULCAST_LAYER_ORDER_NO_OVERRIDE)
        {
            synchronized (sendLayersSyncRoot)
            {
                this.weakOverride = null;
                SimulcastLayer current = getCurrent();
                if (current != null)
                {
                    current.askForKeyframe();
                    this.simulcastLayersChanged(current);
                }
            }
        }
        else
        {
            SimulcastLayer override = simulcastReceiver == null ? null : simulcastReceiver.getSimulcastLayer(overrideOrder);
            if (override != null)
            {
                synchronized (sendLayersSyncRoot)
                {
                    this.weakOverride
                        = new WeakReference<SimulcastLayer>(override);
                    override.askForKeyframe();
                    this.simulcastLayersChanged(override);
                }
            }

        }
    }

    /**
     * A thread safe cyclic counter.
     */
    static class CyclicCounter {

        private final int maxVal;
        private final AtomicInteger ai = new AtomicInteger(0);

        public CyclicCounter(int maxVal) {
            this.maxVal = maxVal;
        }

        public int cyclicallyIncrementAndGet() {
            int curVal, newVal;
            do {
                curVal = this.ai.get();
                newVal = (curVal + 1) % this.maxVal;
                // note that this doesn't guarantee fairness
            } while (!this.ai.compareAndSet(curVal, newVal));
            return newVal;
        }

    }

    /**
     * Multitone pattern with Lazy Initialization.
     */
    static class CyclicCounters
    {
        private final Map<Integer, CyclicCounter> instances
            = new ConcurrentHashMap<Integer, CyclicCounter>();
        private Lock createLock = new ReentrantLock();

        CyclicCounter getOrCreate(Integer key, int maxVal) {
            CyclicCounter instance = instances.get(key);
            if (instance == null) {
                createLock.lock();
                try {
                    if (instance == null) {
                        instance = new CyclicCounter(maxVal);
                        instances.put(key, instance);
                    }
                } finally {
                    createLock.unlock();
                }
            }
            return instance;
        }
    }

    /**
     * Holds the configuration options for the <tt>SwitchingSimulcastSender</tt>.
     *
     * @author George Politis
     */
    static class SwitchingModeOptions
    {
        /**
         *
         */
        private Integer nextOrder;

        /**
         *
         */
        private Integer minNextSeen;

        /**
         * A switch that is urgent (e.g. because of a layer drop).
         */
        private boolean urgent;

        /**
         * A switch that requires a key frame.
         */
        private boolean hardSwitch;

        /**
         *
         * @return
         */
        public Integer getMinNextSeen()
        {
            return minNextSeen;
        }

        /**
         *
         * @param minNextSeen
         */
        public void setMinNextSeen(Integer minNextSeen)
        {
            this.minNextSeen = minNextSeen;
        }

        /**
         *
         * @param urgent
         */
        public void setUrgent(boolean urgent)
        {
            this.urgent = urgent;
        }

        /**
         *
         * @param nextOrder
         */
        public void setNextOrder(Integer nextOrder)
        {
            this.nextOrder = nextOrder;
        }

        /**
         *
         * @param hardSwitch
         */
        public void setHardSwitch(boolean hardSwitch)
        {
            this.hardSwitch = hardSwitch;
        }

        /**
         *
         * @return
         */
        public Integer getNextOrder()
        {
            return nextOrder;
        }

        /**
         *
         * @return
         */
        public boolean isHardSwitch()
        {
            return hardSwitch;
        }

        /**
         *
         * @return
         */
        public boolean isUrgent()
        {
            return urgent;
        }
    }
}

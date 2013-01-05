/*
 * Copyright 2011 The Netty Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package io.netty.handler.traffic;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerAdapter;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundByteHandler;
import io.netty.channel.ChannelOutboundByteHandler;
import io.netty.channel.ChannelPromise;
import io.netty.util.Attribute;
import io.netty.util.AttributeKey;

import java.util.concurrent.TimeUnit;

/**
 * AbstractTrafficShapingHandler allows to limit the global bandwidth
 * (see {@link GlobalTrafficShapingHandler}) or per session
 * bandwidth (see {@link ChannelTrafficShapingHandler}), as traffic shaping.
 * It allows you to implement an almost real time monitoring of the bandwidth using
 * the monitors from {@link TrafficCounter} that will call back every checkInterval
 * the method doAccounting of this handler.<br>
 * <br>
 *
 * If you want for any particular reasons to stop the monitoring (accounting) or to change
 * the read/write limit or the check interval, several methods allow that for you:<br>
 * <ul>
 * <li><tt>configure</tt> allows you to change read or write limits, or the checkInterval</li>
 * <li><tt>getTrafficCounter</tt> allows you to have access to the TrafficCounter and so to stop
 * or start the monitoring, to change the checkInterval directly, or to have access to its values.</li>
 * </ul>
 */
public abstract class AbstractTrafficShapingHandler extends ChannelHandlerAdapter
        implements ChannelInboundByteHandler, ChannelOutboundByteHandler {

    /**
     * Default delay between two checks: 1s
     */
    public static final long DEFAULT_CHECK_INTERVAL = 1000;

    /**
     * Default minimal time to wait
     */
    private static final long MINIMAL_WAIT = 10;

    /**
     * Traffic Counter
     */
    protected TrafficCounter trafficCounter;

    /**
     * Limit in B/s to apply to write
     */
    private long writeLimit;

    /**
     * Limit in B/s to apply to read
     */
    private long readLimit;

    /**
     * Delay between two performance snapshots
     */
    protected long checkInterval = DEFAULT_CHECK_INTERVAL; // default 1 s

    private static final AttributeKey<Boolean> READ_SUSPENDED = new AttributeKey<Boolean>("readSuspended");
    private static final AttributeKey<Runnable> REOPEN_TASK = new AttributeKey<Runnable>("reopenTask");
    private static final AttributeKey<Runnable> BUFFER_UPDATE_TASK = new AttributeKey<Runnable>("bufferUpdateTask");

    /**
     *
     * @param newTrafficCounter the TrafficCounter to set
     */
    void setTrafficCounter(TrafficCounter newTrafficCounter) {
        trafficCounter = newTrafficCounter;
    }

    /**
     * @param writeLimit
     *          0 or a limit in bytes/s
     * @param readLimit
     *          0 or a limit in bytes/s
     * @param checkInterval
     *          The delay between two computations of performances for
     *            channels or 0 if no stats are to be computed
     */
    protected AbstractTrafficShapingHandler(long writeLimit, long readLimit,
                                            long checkInterval) {
        this.writeLimit = writeLimit;
        this.readLimit = readLimit;
        this.checkInterval = checkInterval;
    }

    /**
     * Constructor using default Check Interval
     *
     * @param writeLimit
     *          0 or a limit in bytes/s
     * @param readLimit
     *          0 or a limit in bytes/s
     */
    protected AbstractTrafficShapingHandler(long writeLimit, long readLimit) {
        this(writeLimit, readLimit, DEFAULT_CHECK_INTERVAL);
    }

    /**
     * Constructor using NO LIMIT and default Check Interval
     */
    protected AbstractTrafficShapingHandler() {
        this(0, 0, DEFAULT_CHECK_INTERVAL);
    }

    /**
     * Constructor using NO LIMIT
     *
     * @param checkInterval
     *          The delay between two computations of performances for
     *            channels or 0 if no stats are to be computed
     */
    protected AbstractTrafficShapingHandler(long checkInterval) {
        this(0, 0, checkInterval);
    }

    /**
     * Change the underlying limitations and check interval.
     *
     * @param newWriteLimit The new write limit (in bytes)
     * @param newReadLimit The new read limit (in bytes)
     * @param newCheckInterval The new check interval (in milliseconds)
     */
    public void configure(long newWriteLimit, long newReadLimit,
            long newCheckInterval) {
        configure(newWriteLimit, newReadLimit);
        configure(newCheckInterval);
    }

    /**
     * Change the underlying limitations.
     *
     * @param newWriteLimit The new write limit (in bytes)
     * @param newReadLimit The new read limit (in bytes)
     */
    public void configure(long newWriteLimit, long newReadLimit) {
        writeLimit = newWriteLimit;
        readLimit = newReadLimit;
        if (trafficCounter != null) {
            trafficCounter.resetAccounting(System.currentTimeMillis() + 1);
        }
    }

    /**
     * Change the check interval.
     *
     * @param newCheckInterval The new check interval (in milliseconds)
     */
    public void configure(long newCheckInterval) {
        checkInterval = newCheckInterval;
        if (trafficCounter != null) {
            trafficCounter.configure(checkInterval);
        }
    }

    /**
     * Called each time the accounting is computed from the TrafficCounters.
     * This method could be used for instance to implement almost real time accounting.
     *
     * @param counter
     *            the TrafficCounter that computes its performance
     */
    @SuppressWarnings("unused")
    protected void doAccounting(TrafficCounter counter) {
        // NOOP by default
    }

    /**
     * Class to implement setReadable at fix time
     */
    private static final class ReopenReadTimerTask implements Runnable {
        final ChannelHandlerContext ctx;
        ReopenReadTimerTask(ChannelHandlerContext ctx) {
            this.ctx = ctx;
        }

        @Override
        public void run() {
            ctx.attr(READ_SUSPENDED).set(false);
            ctx.read();
        }
    }

    /**
    *
    * @return the time that should be necessary to wait to respect limit. Can
    *         be negative time
    */
    private static long getTimeToWait(long limit, long bytes, long lastTime,
            long curtime) {
        long interval = curtime - lastTime;
        if (interval == 0) {
            // Time is too short, so just lets continue
            return 0;
        }
        return (bytes * 1000 / limit - interval / 10) * 10;
    }

    @Override
    public ByteBuf newInboundBuffer(ChannelHandlerContext ctx) throws Exception {
        return ctx.nextInboundByteBuffer();
    }

    @Override
    public void discardInboundReadBytes(ChannelHandlerContext ctx) throws Exception {
        // NOOP
    }

    @Override
    public void freeInboundBuffer(ChannelHandlerContext ctx) throws Exception {
        // do nothing
    }

    @Override
    public ByteBuf newOutboundBuffer(ChannelHandlerContext ctx) throws Exception {
        return ctx.nextOutboundByteBuffer();
    }

    @Override
    public void discardOutboundReadBytes(ChannelHandlerContext ctx) throws Exception {
        // NOOP
    }

    @Override
    public void freeOutboundBuffer(ChannelHandlerContext ctx) throws Exception {
        // do nothing
    }

    @Override
    public void inboundBufferUpdated(final ChannelHandlerContext ctx) throws Exception {
        ByteBuf buf = ctx.inboundByteBuffer();
        long curtime = System.currentTimeMillis();
        long size = buf.readableBytes();

        if (trafficCounter != null) {
            trafficCounter.bytesRecvFlowControl(size);
            if (readLimit == 0) {
                // no action
                ctx.fireInboundBufferUpdated();

                return;
            }

            // compute the number of ms to wait before reopening the channel
            long wait = getTimeToWait(readLimit,
                                      trafficCounter.getCurrentReadBytes(),
                                      trafficCounter.getLastTime(), curtime);
            if (wait >= MINIMAL_WAIT) { // At least 10ms seems a minimal
                // time in order to
                // try to limit the traffic
                if (!ctx.attr(READ_SUSPENDED).get()) {
                    ctx.attr(READ_SUSPENDED).set(true);

                    // Create a Runnable to reactive the read if needed. If one was create before it will just be
                    // reused to limit object creation
                    Attribute<Runnable> attr  = ctx.attr(REOPEN_TASK);
                    Runnable reopenTask = attr.get();
                    if (reopenTask == null) {
                        reopenTask = new ReopenReadTimerTask(ctx);
                        attr.set(reopenTask);
                    }
                    ctx.executor().schedule(reopenTask, wait,
                                                   TimeUnit.MILLISECONDS);
                } else {
                    // Create a Runnable to update the next handler in the chain. If one was create before it will
                    // just be reused to limit object creation
                    Attribute<Runnable> attr  = ctx.attr(BUFFER_UPDATE_TASK);
                    Runnable bufferUpdateTask = attr.get();
                    if (bufferUpdateTask == null) {
                        bufferUpdateTask = new Runnable() {
                            @Override
                            public void run() {
                                ctx.fireInboundBufferUpdated();
                            }
                        };
                        attr.set(bufferUpdateTask);
                    }
                    ctx.executor().schedule(bufferUpdateTask, wait, TimeUnit.MILLISECONDS);
                    return;
                }
            }
        }
        ctx.fireInboundBufferUpdated();
    }

    @Override
    public void read(ChannelHandlerContext ctx) {
        if (!ctx.attr(READ_SUSPENDED).get()) {
            ctx.read();
        }
    }

    @Override
    public void flush(final ChannelHandlerContext ctx, final ChannelPromise promise) throws Exception {
        long curtime = System.currentTimeMillis();
        long size = ctx.outboundByteBuffer().readableBytes();

        if (trafficCounter != null) {
            trafficCounter.bytesWriteFlowControl(size);
            if (writeLimit == 0) {
                ctx.flush(promise);
                return;
            }
            // compute the number of ms to wait before continue with the
            // channel
            long wait = getTimeToWait(writeLimit,
                    trafficCounter.getCurrentWrittenBytes(),
                    trafficCounter.getLastTime(), curtime);
            if (wait >= MINIMAL_WAIT) {
                ctx.executor().schedule(new Runnable() {
                    @Override
                    public void run() {
                        ctx.flush(promise);
                    }
                }, wait, TimeUnit.MILLISECONDS);
                return;
            }
        }
        ctx.flush(promise);
    }

    /**
     *
     * @return the current TrafficCounter (if
     *         channel is still connected)
     */
    public TrafficCounter getTrafficCounter() {
        return trafficCounter;
    }

    @Override
    public void beforeRemove(ChannelHandlerContext ctx) {
        if (trafficCounter != null) {
            trafficCounter.stop();
        }
    }

    @Override
    public String toString() {
        return "TrafficShaping with Write Limit: " + writeLimit +
                " Read Limit: " + readLimit + " and Counter: " +
                (trafficCounter != null? trafficCounter.toString() : "none");
    }
}

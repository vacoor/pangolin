package com.github.pangolin.routing.acceptor.tun.net.v2.tcp.pipeline;

import com.github.pangolin.routing.acceptor.tun.net.handler.support.TcpPacketBuf;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.internal.FourTuple;
import io.netty.channel.*;

import java.net.SocketAddress;

/**
 * Virtual Netty Channel representing a single TCP connection (4-tuple).
 * Modelled after {@code AbstractHttp2StreamChannel}: a per-multiplexed-connection channel
 * registered on a Worker EventLoop selected by the TUN EventLoop via consistent hashing.
 *
 * <p><b>Thread model</b>:
 * <ul>
 *   <li>Registration: called by TUN EventLoop</li>
 *   <li>All TCP state machine work: executed in {@code assignedWorker} via {@code execute()}</li>
 *   <li>Outbound writes: {@code doWrite()} runs on Worker, {@code parentCtx.write()} auto-routes
 *       to the TUN EventLoop pipeline</li>
 *   <li>Registry cleanup: {@code doClose()} posts {@code deregisterCallback} to TUN EventLoop</li>
 * </ul>
 *
 * <p><b>No {@code @ChannelHandler.Sharable}</b> — each instance is per-connection.
 */
public final class TcpConnectionChannel extends AbstractChannel {

    private static final ChannelMetadata METADATA = new ChannelMetadata(false);

    private final Channel              tunChannel;
    private final FourTuple            fourTuple;
    private final EventLoop            assignedWorker;
    private final Runnable             deregisterCallback;
    private       ChannelHandlerContext parentCtx;
    private volatile boolean           active;
    private final ChannelConfig        config = new DefaultChannelConfig(this);

    /**
     * @param tunChannel          parent TUN Channel (write target for outbound packets)
     * @param fourTuple           the TCP 4-tuple identifying this connection
     * @param assignedWorker      Worker EventLoop bound to this connection (consistent hash)
     * @param deregisterCallback  run on TUN EventLoop when the channel closes
     */
    public TcpConnectionChannel(Channel tunChannel,
                                 FourTuple fourTuple,
                                 EventLoop assignedWorker,
                                 Runnable deregisterCallback) {
        super(null);
        this.tunChannel         = tunChannel;
        this.fourTuple          = fourTuple;
        this.assignedWorker     = assignedWorker;
        this.deregisterCallback = deregisterCallback;
    }

    /** Must be called before {@code worker.register(this)}. */
    public void setParentContext(ChannelHandlerContext parentCtx) {
        this.parentCtx = parentCtx;
    }

    // ── AbstractChannel overrides ────────────────────────────────────────────

    @Override
    public ChannelConfig config() { return config; }

    @Override
    protected AbstractUnsafe newUnsafe() { return new TcpConnectionUnsafe(); }

    /**
     * Bind this channel to exactly the assigned Worker EventLoop.
     * One connection always uses the same thread — no need for locks.
     */
    @Override
    protected boolean isCompatible(EventLoop loop) {
        return loop == assignedWorker;
    }

    /**
     * No real file-descriptor registration: just flip {@code active} to true.
     * {@code AbstractChannel.AbstractUnsafe.register0()} triggers
     * {@code pipeline.fireChannelRegistered()} + {@code fireChannelActive()} after returning.
     *
     * <p>Do NOT manually call those fire methods here — AbstractChannel does it automatically
     * and a duplicate call would confuse handlers.
     */
    @Override
    protected void doRegister() {
        active = true;
    }

    /**
     * No file-descriptor deregistration.
     * {@code AbstractChannel} handles {@code fireChannelInactive()} / {@code fireChannelUnregistered()}.
     */
    @Override
    protected void doDeregister() { /* no-op */ }

    @Override
    protected void doBind(SocketAddress local) { /* no-op — passive only */ }

    @Override
    protected void doDisconnect() { doClose(); }

    /**
     * Close lifecycle:
     * <ol>
     *   <li>Flip {@code active} → false.</li>
     *   <li>{@code AbstractUnsafe} detects wasActive && !isActive → fires channelInactive().</li>
     *   <li>Handler's channelInactive() calls {@code conn.close()} (cancel timers, release ext state).</li>
     *   <li>{@code AbstractUnsafe.deregister0()} fires channelUnregistered().</li>
     * </ol>
     * {@code deregisterCallback} is posted to the TUN EventLoop to maintain single-writer
     * constraint on the registry (plain {@code HashMap}, no {@code ConcurrentMap} needed).
     */
    @Override
    protected void doClose() {
        if (!active) return;
        active = false;
        tunChannel.eventLoop().execute(deregisterCallback);
    }

    /**
     * Push-model: inbound packets are injected via {@link #fireChildRead}, not by the selector.
     * Future: can link to {@code RCV.WND} for upstream back-pressure (currently no-op).
     */
    @Override
    protected void doBeginRead() { /* no-op */ }

    /**
     * Write all pending outbound messages to the parent TUN pipeline.
     * Runs on Worker EventLoop; {@code parentCtx.write()} detects the cross-thread call and
     * submits to the TUN EventLoop task queue automatically — no explicit wrapping needed.
     */
    @Override
    protected void doWrite(ChannelOutboundBuffer buf) throws Exception {
        for (;;) {
            Object msg = buf.current();
            if (msg == null) break;
            parentCtx.write(msg);
            buf.remove();
        }
        parentCtx.flush();
    }

    @Override protected SocketAddress localAddress0()  { return fourTuple.local(); }
    @Override protected SocketAddress remoteAddress0() { return fourTuple.remote(); }
    @Override public    boolean isOpen()               { return active; }
    @Override public    boolean isActive()             { return active; }
    @Override public    ChannelMetadata metadata()     { return METADATA; }

    // ── Inbound packet injection ─────────────────────────────────────────────

    /**
     * Inject an inbound TCP packet into this connection's pipeline.
     * Named after {@code AbstractHttp2StreamChannel.fireChildRead()} — the Netty fire* convention.
     *
     * <p>Must be called on the {@code assignedWorker} EventLoop.
     *
     * <p><b>Reference-count contract (this method owns the lifecycle):</b>
     * <ul>
     *   <li>Active path: ownership is transferred to the pipeline. The pipeline handler
     *       ({@code SimpleChannelInboundHandler} auto-release, or {@code TailContext}) releases
     *       {@code pkt} exactly once. Do NOT release {@code pkt} in the caller after this call.</li>
     *   <li>Inactive path: this method releases {@code pkt} directly via
     *       {@code ReferenceCountUtil.release()}. The caller still must not release after this call.</li>
     * </ul>
     * The caller (TUN EventLoop) is responsible only for the reference it added via
     * {@code pkt.retain()} before cross-thread dispatch and its own original reference.
     */
    public void fireChildRead(TcpPacketBuf pkt) {
        assert eventLoop().inEventLoop();
        if (!isActive()) {
            // Channel already closed or registration failed — release the reference
            // the Worker lambda was given (the caller's retain() in TcpMultiplexHandler).
            io.netty.util.ReferenceCountUtil.release(pkt);
            return;
        }
        // Transfer ownership to the pipeline. The handler (or TailContext) will release pkt.
        pipeline().fireChannelRead(pkt);
        pipeline().fireChannelReadComplete();
    }

    public FourTuple fourTuple() { return fourTuple; }

    // ── Unsafe ──────────────────────────────────────────────────────────────

    private final class TcpConnectionUnsafe extends AbstractUnsafe {
        @Override
        public void connect(SocketAddress remote, SocketAddress local, ChannelPromise promise) {
            promise.setFailure(new UnsupportedOperationException("TcpConnectionChannel is passive-only"));
        }
    }
}

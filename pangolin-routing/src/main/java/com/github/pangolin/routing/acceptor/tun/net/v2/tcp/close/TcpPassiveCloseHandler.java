package com.github.pangolin.routing.acceptor.tun.net.v2.tcp.close;

import com.github.pangolin.routing.acceptor.tun.net.handler.support.TcpPacketBuf;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.connection.TcpConnection;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.connection.TcpConnectionState;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.core.TcpSegmentValidator;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.core.TcpSegmenter;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Passive close state machine: CLOSE_WAIT → LAST_ACK → CLOSED.
 *
 * <p>The key design: {@link #close(ChannelHandlerContext, ChannelPromise)} overrides
 * {@link ChannelDuplexHandler#close} to <b>delay</b> the actual Netty channel close.
 * When the application calls {@code channel.close()}, this handler intercepts it:
 * <ol>
 *   <li>Sends FIN.</li>
 *   <li>Transitions to LAST_ACK.</li>
 *   <li>Saves the {@code closePromise}.</li>
 *   <li>Does NOT call {@code super.close()} yet.</li>
 * </ol>
 * When the peer's ACK arrives (step {@link #channelRead}):
 * <ol>
 *   <li>Calls {@code ctx.close(closePromise)} — this triggers the real Netty lifecycle
 *       ({@code doClose()} → {@code deregisterCallback} → registry removal).</li>
 * </ol>
 */
public final class TcpPassiveCloseHandler extends ChannelDuplexHandler {

    private static final Logger log = LoggerFactory.getLogger(TcpPassiveCloseHandler.class);

    private final TcpConnection conn;
    private ChannelPromise      closePromise;

    public TcpPassiveCloseHandler(TcpConnection conn) {
        this.conn = conn;
    }

    // ── Inbound ────────────────────────────────────────────────────────────

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        if (!(msg instanceof TcpPacketBuf)) {
            ctx.fireChannelRead(msg);
            return;
        }
        TcpPacketBuf pkt = (TcpPacketBuf) msg;
        try {
            // Validation (preProcess + processAck) was handled by TcpSegmentValidator.
            TcpSegmentValidator.AckResult ackResult = conn.getAttr(TcpSegmentValidator.ACK_RESULT_KEY);
            if (ackResult != TcpSegmentValidator.AckResult.NONE) {
                // tcp_data_snd_check: flush any queued data whose window just re-opened.
                TcpSegmenter.INSTANCE.sendPending(conn);
            }

            // LAST_ACK: close only when the ACK covers our FIN (SND.UNA == SND.NXT).
            // A stale ACK that hasn't yet reached the FIN sequence must be ignored.
            if (conn.state() == TcpConnectionState.LAST_ACK
                    && pkt.isAck()
                    && conn.sndUna() == conn.sndNxt()) {
                log.debug("[TCP] [PASSIVE-CLOSE] FIN ACK'd in LAST_ACK — closing channel");
                // Trigger real Netty close: doClose() → deregisterCallback → registry.remove()
                ChannelPromise p = closePromise != null ? closePromise : ctx.newPromise();
                ctx.close(p);
            }
            // CLOSE_WAIT: application drives close via close(); nothing further to do here.
        } finally {
            // pkt is consumed here (not forwarded to TailContext), so we must release it.
            pkt.release();
        }
    }

    // ── Outbound ──────────────────────────────────────────────────────────

    /**
     * Intercept application close: send FIN instead of closing immediately.
     * The actual Netty channel close is deferred until the peer ACKs our FIN.
     */
    @Override
    public void close(ChannelHandlerContext ctx, ChannelPromise promise) {
        if (conn.state() == TcpConnectionState.CLOSE_WAIT) {
            log.debug("[TCP] [PASSIVE-CLOSE] close() called — sending FIN, entering LAST_ACK");
            this.closePromise = promise;
            // Notify validator so an RST arriving in LAST_ACK can abort on the correct promise.
            TcpSegmentValidator v = ctx.pipeline().get(TcpSegmentValidator.class);
            if (v != null) v.closePromise(promise);
            TcpCloseMachine.beginLastAckClose(conn);
            // Do NOT call super.close(): we wait for LAST_ACK's ACK in channelRead
        } else {
            // Already in LAST_ACK or later: pass through
            ctx.close(promise);
        }
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        conn.close();
        ctx.fireChannelInactive();
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        log.warn("[TCP] [PASSIVE-CLOSE] Exception — closing", cause);
        TcpCloseMachine.abortiveClose(ctx, conn, closePromise);
    }
}

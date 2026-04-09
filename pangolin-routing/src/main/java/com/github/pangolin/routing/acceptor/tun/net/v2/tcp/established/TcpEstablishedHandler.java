package com.github.pangolin.routing.acceptor.tun.net.v2.tcp.established;

import com.github.pangolin.routing.acceptor.tun.net.handler.support.TcpPacketBuf;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.close.TcpPassiveCloseHandler;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.close.TcpActiveCloseHandler;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.connection.TcpConnection;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.connection.TcpConnectionState;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.core.TcpSegmenter;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.internal.TcpConstants;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.timer.TcpTimerScheduler;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * TCP data-transfer pipeline stage.
 *
 * <p>Processes all segments while the connection is ESTABLISHED:
 * <ul>
 *   <li>Validates sequence numbers (RFC 9293 §3.4)</li>
 *   <li>Processes ACKs (RFC 9293 / RFC 5681 / RFC 6298)</li>
 *   <li>Queues and delivers received data (RFC 9293 §3.7)</li>
 *   <li>Handles incoming FIN → transitions to passive close</li>
 *   <li>Schedules delayed ACK (RFC 9293 §3.8.6.2.2)</li>
 * </ul>
 *
 * <p>Outbound writes ({@code ctx.write(ByteBuf)}) are enqueued in {@code TcpSendBuffer}
 * and transmitted by {@link TcpSegmenter}.
 *
 * <p>Runs entirely on the connection's assigned Worker EventLoop.
 */
public final class TcpEstablishedHandler extends ChannelDuplexHandler {

    private static final Logger log = LoggerFactory.getLogger(TcpEstablishedHandler.class);

    private final TcpConnection conn;
    private boolean delAckPending = false;

    public TcpEstablishedHandler(TcpConnection conn) {
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

        // RST: unconditionally close
        if (pkt.isRst()) {
            log.debug("[TCP] [ESTABLISHED] RST received — closing");
            ctx.channel().close();
            return;
        }

        // Segment validation (sequence number + PAWS)
        if (!TcpSegmentValidator.INSTANCE.validate(conn, pkt)) {
            return;
        }

        // ACK processing
        TcpAckHandler.INSTANCE.onAck(conn, pkt);

        // Data
        if (pkt.tcpPayloadLength() > 0 && conn.state().canReceive()) {
            TcpDataHandler.INSTANCE.onData(ctx, conn, pkt);
            scheduleDelayedAck(ctx);
        }

        // FIN from peer → passive close
        if (pkt.isFin() && conn.state().canReceive()) {
            conn.rcvNxt(conn.rcvNxt() + 1);   // FIN consumes one sequence number
            conn.state(TcpConnectionState.CLOSE_WAIT);
            log.debug("[TCP] [ESTABLISHED] FIN received — entering CLOSE_WAIT");
            // Send ACK immediately
            TcpSegmenter.INSTANCE.sendAck(conn);
            // Replace handler with passive close handler
            ctx.pipeline().replace(this, "close", new TcpPassiveCloseHandler(conn));
        }
    }

    // ── Outbound ───────────────────────────────────────────────────────────

    /**
     * Intercept application writes: enqueue into send buffer and attempt to transmit.
     */
    @Override
    public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
        if (msg instanceof ByteBuf) {
            ByteBuf data = (ByteBuf) msg;
            conn.sendBuffer().enqueue(data);
            TcpSegmenter.INSTANCE.sendPending(conn);
            promise.setSuccess();
        } else {
            ctx.write(msg, promise);
        }
    }

    /**
     * Initiate active close: send FIN, transition to FIN_WAIT_1.
     */
    @Override
    public void close(ChannelHandlerContext ctx, ChannelPromise promise) {
        log.debug("[TCP] [ESTABLISHED] close() called — initiating active close");
        conn.state(TcpConnectionState.FIN_WAIT_1);
        TcpSegmenter.INSTANCE.sendFin(conn);
        ctx.pipeline().replace(this, "close", new TcpActiveCloseHandler(conn, promise));
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        conn.close();
        ctx.fireChannelInactive();
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        log.warn("[TCP] [ESTABLISHED] Exception — closing", cause);
        ctx.channel().close();
    }

    // ── Delayed ACK ───────────────────────────────────────────────────────

    private void scheduleDelayedAck(ChannelHandlerContext ctx) {
        if (delAckPending) return;
        delAckPending = true;
        TcpTimerScheduler.INSTANCE.scheduleDelayedAck(conn, TcpConstants.DELAYED_ACK_MS, () -> {
            delAckPending = false;
            if (ctx.channel().isActive()) {
                TcpSegmenter.INSTANCE.sendAck(conn);
            }
        });
    }
}

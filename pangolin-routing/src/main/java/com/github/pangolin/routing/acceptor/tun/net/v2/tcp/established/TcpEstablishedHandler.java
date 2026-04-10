package com.github.pangolin.routing.acceptor.tun.net.v2.tcp.established;

import com.github.pangolin.routing.acceptor.tun.net.handler.support.TcpPacketBuf;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.close.TcpPassiveCloseHandler;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.close.TcpActiveCloseHandler;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.connection.TcpConnection;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.connection.TcpConnectionState;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.core.TcpSegmenter;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.internal.TcpConstants;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.timer.TcpTimerScheduler;

import static com.github.pangolin.routing.acceptor.tun.net.v2.tcp.internal.TcpConstants.ACK_NOW;
import static com.github.pangolin.routing.acceptor.tun.net.v2.tcp.internal.TcpConstants.ACK_SCHED;
import static com.github.pangolin.routing.acceptor.tun.net.v2.tcp.internal.TcpConstants.ACK_TIMER;
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
    /**
     * ACK-pending bitmask — mirrors Linux {@code icsk_ack.pending}.
     * Bits: {@link TcpConstants#ACK_SCHED} | {@link TcpConstants#ACK_TIMER} | {@link TcpConstants#ACK_NOW}
     */
    private int ackPending = 0;

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

        // RST: validate sequence before closing (RFC 9293 §3.5.2)
        if (pkt.isRst()) {
            if (TcpSegmentValidator.isRstAcceptable(conn, pkt)) {
                log.debug("[TCP] [ESTABLISHED] RST received — closing");
                ctx.channel().close();
            }
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
            ackPending |= ACK_SCHED;   // we owe the peer an ACK for this data
            TcpDataHandler.INSTANCE.onData(ctx, conn, pkt);
            // write() called synchronously inside onData() (app handler piggybacked a response)
            // will have cleared ACK_SCHED; the ackSndCheck() below handles that cleanly.
        }

        // FIN from peer → passive close
        if (pkt.isFin() && conn.state().canReceive()) {
            conn.rcvNxt(conn.rcvNxt() + 1);   // FIN consumes one sequence number
            conn.state(TcpConnectionState.CLOSE_WAIT);
            log.debug("[TCP] [ESTABLISHED] FIN received — entering CLOSE_WAIT");
            // FIN must be ACK'd immediately without delay (RFC 9293 §3.10.7.4).
            // ACK_NOW implies ACK_SCHED: setting both ensures ackSndCheck() doesn't
            // skip the send when no data was received in the same segment.
            ackPending |= ACK_SCHED | ACK_NOW;
            // Replace handler with passive close handler
            ctx.pipeline().replace(this, "close", new TcpPassiveCloseHandler(conn));
        }

        // Single ACK decision point — mirrors Linux tcp_ack_snd_check().
        ackSndCheck(ctx);
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
            int sndNxtBefore = conn.sndNxt();
            TcpSegmenter.INSTANCE.sendPending(conn);
            // If sendPending() actually transmitted a segment (sndNxt advanced),
            // the PSH+ACK already carries the acknowledgment:
            //   sync path  — clear ACK_SCHED before ackSndCheck() at end of channelRead fires
            //   async path — clear ACK_TIMER and cancel the running delayed-ACK timer
            if (conn.sndNxt() != sndNxtBefore) {
                if ((ackPending & ACK_TIMER) != 0) {
                    TcpTimerScheduler.INSTANCE.cancelDelayedAck(conn);
                }
                ackPending &= ~(ACK_SCHED | ACK_TIMER);
            }
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

    // ── ACK scheduling — mirrors Linux tcp_ack_snd_check / __tcp_ack_snd_check ──────────────

    /**
     * Single ACK decision point, called at the end of every {@link #channelRead} invocation.
     * Mirrors Linux {@code tcp_ack_snd_check()}: if ACK_SCHED is clear (write() already
     * piggybacked the ACK in a PSH+ACK), nothing to do; otherwise send now (ACK_NOW) or
     * arm the delayed-ACK timer.
     */
    private void ackSndCheck(ChannelHandlerContext ctx) {
        if ((ackPending & ACK_SCHED) == 0) return;   // ACK already sent (piggybacked)
        ackPending &= ~ACK_SCHED;

        if ((ackPending & ACK_NOW) != 0) {
            // Immediate ACK required (e.g. FIN received — RFC 9293 §3.10.7.4)
            if ((ackPending & ACK_TIMER) != 0) {
                TcpTimerScheduler.INSTANCE.cancelDelayedAck(conn);
            }
            ackPending &= ~(ACK_NOW | ACK_TIMER);
            TcpSegmenter.INSTANCE.sendAck(conn);
        } else {
            scheduleDelayedAck(ctx);
        }
    }

    /** Arm the delayed-ACK timer if not already running ({@code ACK_TIMER} not set). */
    private void scheduleDelayedAck(ChannelHandlerContext ctx) {
        if ((ackPending & ACK_TIMER) != 0) return;   // timer already armed
        ackPending |= ACK_TIMER;
        TcpTimerScheduler.INSTANCE.scheduleDelayedAck(conn, TcpConstants.DELAYED_ACK_MS, () -> {
            ackPending &= ~ACK_TIMER;
            if (ctx.channel().isActive()) {
                TcpSegmenter.INSTANCE.sendAck(conn);
            }
        });
    }
}

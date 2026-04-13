package com.github.pangolin.routing.acceptor.tun.net.v2.tcp.established;

import com.github.pangolin.routing.acceptor.tun.net.handler.support.TcpPacketBuf;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.close.TcpCloseMachine;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.close.TcpActiveCloseHandler;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.close.TcpPassiveCloseHandler;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.connection.TcpConnection;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.connection.TcpConnectionState;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.core.TcpSegmenter;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.core.TcpStateProcessor;
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

import java.nio.channels.ClosedChannelException;

/**
 * ESTABLISHED-state segment processing — RFC 9293 §3.10.7.4.
 *
 * <p>Inbound path ({@link #channelRead}) follows the eight-step procedure from the RFC:
 * <ol>
 *   <li>Sequence number acceptability check (§3.4) + PAWS (RFC 7323 §5)</li>
 *   <li>RST bit — pre-screened with the acceptability test (§3.5.3)</li>
 *   <li>Security — not applicable</li>
 *   <li>SYN bit — rejected by step 1 sequence check; challenge-ACK omitted</li>
 *   <li>ACK field — advance SND.UNA, update window, RFC 5681 CC, RFC 6298 timer</li>
 *   <li>URG bit — not implemented</li>
 *   <li>Segment text — deliver in-order; OFO queue for out-of-order</li>
 *   <li>FIN bit — enter CLOSE_WAIT, ACK immediately</li>
 * </ol>
 *
 * <p>Between steps 5 and 7, {@code tcp_data_snd_check} flushes any queued send data
 * whose window was re-opened by this ACK.
 *
 * <p>After step 8, {@code tcp_ack_snd_check} decides whether to send an ACK immediately
 * or arm the delayed-ACK timer.
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

    /**
     *
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_ipv4.c#tcp_v4_do_rcv">tcp_v4_do_rcv</a>
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_input.c#tcp_rcv_state_process">tcp_rcv_state_process</a>
     */
    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        if (!(msg instanceof TcpPacketBuf)) {
            ctx.fireChannelRead(msg);
            return;
        }
        TcpPacketBuf pkt = (TcpPacketBuf) msg;
        try {
            if (TcpStateProcessor.INSTANCE.preProcess(ctx, conn, pkt, null, log, "ESTABLISHED")) {
                return;
            }

            if (TcpStateProcessor.INSTANCE.processAck(conn, pkt)
                    == TcpStateProcessor.AckResult.INVALID_FUTURE_ACK) {
                return;
            }

            /* ── TCP_SYN_RECV → TCP_ESTABLISHED transition ───────────────────────────
             * Mirrors the TCP_SYN_RECV case in Linux tcp_rcv_state_process(): after the
             * final ACK is accepted and processed, promote the connection to ESTABLISHED
             * before handling any piggybacked data or FIN (RFC 9293 §3.10.7.3).
             *
             * The TcpConnection is created by TcpHandshaker.finishHandshake() in
             * SYN_RECEIVED state (analogous to tcp_create_openreq_child()), and the
             * final ACK packet is replayed into this handler by TcpHandshakeHandler
             * (analogous to tcp_child_process → tcp_rcv_state_process). */
            if (conn.state() == TcpConnectionState.TCP_SYN_RECV) {
                conn.state(TcpConnectionState.TCP_ESTABLISHED);
                log.debug("[TCP] [SYN_RECV] Final ACK accepted — entering ESTABLISHED");
            }

            /* ── tcp_data_snd_check (after step 5) ──────────────────────────────────
             * The ACK may have re-opened a zero peer window.  Flush any queued send
             * data immediately so the peer's credit is consumed without waiting for
             * the application to write again.
             * If a PSH+ACK is emitted here it carries rcvNxt as the acknowledgement,
             * piggybacking any delayed ACK still pending from the previous segment. */
            {
                int sndNxtPrior = conn.sndNxt();
                TcpSegmenter.INSTANCE.sendPending(conn);
                if (conn.sndNxt() != sndNxtPrior && (ackPending & ACK_TIMER) != 0) {
                    TcpTimerScheduler.INSTANCE.cancelDelayedAck(conn);
                    ackPending &= ~ACK_TIMER;
                }
            }

            /* ── Step 7: process the segment text (RFC 9293 §3.10.7.4 seventh) ──────
             * Deliver in-order payload to the application; buffer out-of-order data. */
            if (pkt.tcpPayloadLength() > 0 && conn.state().canReceive()) {
                ackPending |= ACK_SCHED;   // received data — we owe peer an ACK
                TcpDataHandler.INSTANCE.onData(ctx, conn, pkt);
                // write() may be called synchronously from the app handler during onData()
                // (piggybacked response); it clears ACK_SCHED when it sends a PSH+ACK.
            }

            /* ── Step 8: check the FIN bit (RFC 9293 §3.10.7.4 eighth) ─────────────
             * FIN received: advance RCV.NXT, enter CLOSE_WAIT, ACK immediately.
             * RFC 9293 §3.10.7.4 requires the FIN to be acknowledged without delay;
             * ACK_NOW + ACK_SCHED ensure ackSndCheck() sends even if no data arrived. */
            if (pkt.isFin() && conn.state().canReceive()) {
                conn.rcvNxt(conn.rcvNxt() + 1);   // FIN consumes one sequence number
                conn.addShutdown(TcpConstants.RCV_SHUTDOWN);
                conn.state(TcpConnectionState.CLOSE_WAIT);
                log.debug("[TCP] [ESTABLISHED] FIN received — entering CLOSE_WAIT");
                ackPending |= ACK_SCHED | ACK_NOW;
                ctx.pipeline().replace(this, "close", new TcpPassiveCloseHandler(conn));
            }

            /* ── tcp_ack_snd_check (after step 8) ───────────────────────────────────
             * Single ACK decision point (mirrors Linux tcp_ack_snd_check / §5827):
             * send immediately if ACK_NOW, otherwise arm the delayed-ACK timer. */
            ackSndCheck(ctx);
        } finally {
            // pkt is consumed here (not forwarded to TailContext), so we must release it.
            // This is the terminal consumer for all inbound TcpPacketBuf in ESTABLISHED state.
            pkt.release();
        }
    }

    // ── Outbound ───────────────────────────────────────────────────────────

    /**
     * Intercept application writes: enqueue into send buffer and attempt to transmit.
     */
    @Override
    public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
        if (msg instanceof ByteBuf) {
            if (conn.hasShutdown(TcpConstants.SEND_SHUTDOWN)) {
                ((ByteBuf) msg).release();
                promise.setFailure(new ClosedChannelException());
                return;
            }
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
        if (conn.hasShutdown(TcpConstants.SHUTDOWN_MASK)) {
            ctx.close(promise);
            return;
        }
        log.debug("[TCP] [ESTABLISHED] close() called — initiating active close");
        conn.addShutdown(TcpConstants.SEND_SHUTDOWN);
        conn.state(TcpConnectionState.FIN_WAIT_1);
        // Ensure FIN is sequenced after all queued app data.
        TcpSegmenter.INSTANCE.sendPending(conn);
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
        TcpCloseMachine.abortiveClose(ctx, conn, null);
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

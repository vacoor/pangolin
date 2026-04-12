package com.github.pangolin.routing.acceptor.tun.net.v2.tcp.established;

import com.github.pangolin.routing.acceptor.tun.net.handler.support.TcpPacketBuf;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.close.TcpPassiveCloseHandler;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.close.TcpActiveCloseHandler;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.connection.TcpConnection;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.connection.TcpConnectionState;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.core.TcpSegmenter;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.internal.TcpConstants;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.timer.TcpTimerScheduler;

import static com.github.pangolin.routing.acceptor.tun.net.handler.tcp.util.TcpLogUtils.logFormat;
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
            /* ── Flag pre-filter (before RFC steps) ──────────────────────────────────
             * In all post-handshake states every valid segment must carry at least one
             * of {ACK, RST, SYN}.  A segment with none of these is malformed (e.g. bare
             * PSH, bare FIN without ACK, flags=0).  Drop silently without sending ACK —
             * mirrors Linux tcp_rcv_state_process(!ACK && !RST && !SYN → discard). */
            if (!pkt.isAck() && !pkt.isRst() && !pkt.isSyn()) {
                log.warn(logFormat("[TCP] [RCV]", pkt, "Connection reset: Invalid TCP flag(!ACK, !RST, !SYN)"));
                return;
            }

            /* ── Step 2: check the RST bit (RFC 9293 §3.10.7.4 + RFC 5961 §3.2) ──────
             * RST has its own acceptability test (§3.5.2) — three outcomes:
             *   DROP          : SEG.SEQ outside window — silently discard.
             *   RESET         : SEG.SEQ == RCV.NXT    — close connection.
             *   CHALLENGE_ACK : SEG.SEQ in window but != RCV.NXT — send challenge ACK
             *                   to let peer correct itself (blind-RST attack mitigation).
             * Screened before step 1 because RST is exempt from PAWS (RFC 7323 §5). */
            if (pkt.isRst()) {
                switch (TcpSegmentValidator.checkRst(conn, pkt)) {
                    case RESET:
                        // FIXME skip send FIN.
                        log.debug("[TCP] [ESTABLISHED] RST accepted (seq==RCV.NXT) — closing");
                        ctx.channel().close();
                        break;
                    case CHALLENGE_ACK:
                        log.debug("[TCP] [ESTABLISHED] RST in window but seq!=RCV.NXT — challenge ACK");
                        TcpSegmenter.INSTANCE.sendAck(conn);
                        break;
                    default: // DROP
                        break;
                }
                return;
            }

            /* ── Step 1: check sequence number + PAWS (RFC 9293 §3.4 + RFC 7323 §5) ─
             * Unacceptable segment: send ACK (unless RST) and drop.
             * PAWS check runs first (§3.4 note): TSval regression → send ACK and drop. */
            if (!TcpSegmentValidator.INSTANCE.validate(conn, pkt)) {
                return;
            }

            /* ── Step 5: check the ACK field (RFC 9293 §3.10.7.4 fifth) ─────────────
             * Advance SND.UNA, update SND.WND, sample RTT (Karn's alg.), notify CC
             * (RFC 5681), and manage the RFC 6298 retransmit timer (§5.2 / §5.3). */
            TcpAckHandler.INSTANCE.onAck(conn, pkt);

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
        // Flush any buffered send data before FIN: if the peer's window was
        // temporarily closed, data may be queued in sendBuffer.writeQueue.
        // Sending it here ensures FIN is sequenced after all application data.
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
        // FIXME RST
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

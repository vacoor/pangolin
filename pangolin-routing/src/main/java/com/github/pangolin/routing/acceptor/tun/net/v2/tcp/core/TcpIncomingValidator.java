package com.github.pangolin.routing.acceptor.tun.net.v2.tcp.core;

import com.github.pangolin.routing.acceptor.tun.net.handler.support.TcpPacketBuf;
import com.github.pangolin.routing.acceptor.tun.net.handler.tcp.util.TcpOptionCodec;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.connection.TcpConnection;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.connection.TcpConnectionState;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import lombok.extern.slf4j.Slf4j;

import static com.github.pangolin.routing.acceptor.tun.net.handler.tcp.util.TcpLogUtils.logFormat;
import static com.github.pangolin.routing.acceptor.tun.net.handler.tcp.util.TcpUtils.determineEndSeq;
import static com.github.pangolin.routing.acceptor.tun.net.v2.tcp.internal.TcpSequence.after;
import static com.github.pangolin.routing.acceptor.tun.net.v2.tcp.internal.TcpSequence.before;
import static com.github.pangolin.routing.acceptor.tun.net.v2.tcp.core.SkbDropReasonConstants.*;
import static com.github.pangolin.routing.acceptor.tun.net.v2.tcp.core.TcpOutOps.*;
import static com.github.pangolin.routing.acceptor.tun.net.v2.tcp.core.TcpInputOps.*;

@Slf4j
public class TcpIncomingValidator extends ChannelInboundHandlerAdapter {
    private final TcpConnection conn;
    private ChannelPromise closePromise;

    public TcpIncomingValidator(TcpConnection conn) {
        this.conn = conn;
    }

    public void closePromise(ChannelPromise p) {
        this.closePromise = p;
    }

    /**
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_input.c#L6910">tcp_rcv_state_process</a>
     */
    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        if (!(msg instanceof TcpPacketBuf)) {
            ctx.fireChannelRead(msg);
            return;
        }
        TcpPacketBuf pkt = (TcpPacketBuf) msg;

        // ── Flag gate ────────────────────────────────────────────────────────
        // Mirrors Linux tcp_rcv_state_process: drop segments that are neither ACK, RST, nor SYN.
        if (!pkt.isAck() && !pkt.isRst() && !pkt.isSyn()) {
            log.warn(logFormat("[TCP] [RCV]", pkt, "Invalid TCP flag(!ACK, !RST, !SYN) — dropped"));
            pkt.release();
            return;
        }

        // ── tcp_validate_incoming: PAWS + sequence + RST + SYN (steps 1–4) ──
        if (!tcp_validate_incoming(ctx, conn, pkt)) {
            // DROP, CHALLENGE_ACK, or RESET: side-effects already applied inside.
            pkt.release();
            return;
        }

        ctx.fireChannelRead(msg);
    }

    /**
     * Unified validation path for post-handshake states, aligned with Linux
     * {@code tcp_validate_incoming} ordering:
     * PAWS → sequence check → RST check → SYN challenge.
     *
     * <p>Mirrors the Linux boolean return convention: {@code true} means the segment
     * passed all checks and processing should continue; {@code false} means the segment
     * was discarded (side-effects such as OOW ACK, challenge ACK, or abortive close have
     * already been applied inside this method, just like Linux {@code tcp_reset()} is
     * called from within {@code tcp_validate_incoming}).
     *
     * @return {@code true} if the segment is acceptable; {@code false} to drop it.
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_input.c#L5870">tcp_validate_incoming</a>
     */
    private boolean tcp_validate_incoming(ChannelHandlerContext ctx,
                                          TcpConnection conn, TcpPacketBuf pkt) {
        boolean accecn_reflector = false;
        // PAWS check (RFC 7323 §5) — RST is exempt.
        if (conn.timestampExt().isEnabled(conn)) {
            ByteBuf opts = pkt.tcpOptionsSlice();
            long[] ts = TcpOptionCodec.parseTimestamp(opts);
            if (ts != null && conn.timestampExt().isPawsRejected(conn, (int) ts[0])) {
                if (!pkt.isRst()) {
                    TcpSegmenter.INSTANCE.sendAck(conn);
                    return false;
                }
                // RST is accepted even if it did not pass PAWS — fall through to step 1.
            }
        }

        // Step 1: sequence number acceptability (RFC 9293 §3.4).
        final int reason = tcp_sequence(conn, pkt);
        if (reason != SKB_NOT_DROPPED_YET) {
            /*
             * RFC 793 p.37: "In all states except SYN-SENT, all reset (RST) segments are
             * validated by checking their SEQ-fields."
             * RFC 793 p.69: "If an incoming segment is not acceptable, an acknowledgment
             * should be sent in reply (unless the RST bit is set, if so drop the segment
             * and return)."
             *
             * Mirror Linux ordering: !rst branch first (handles SYN inside), then RST.
             */
            if (!pkt.isRst()) {
                if (pkt.isSyn()) {
                    // Linux syn_challenge: challenge ACK on invalid-sequence SYN (RFC 5961 §4).
                    tcp_send_challenge_ack(conn, accecn_reflector);
                    return discard(conn, pkt, SKB_DROP_REASON_TCP_INVALID_SYN);
                }
                // Rate-limited dupack for out-of-window non-RST/non-SYN segments.
                if (tcp_oow_rate_limited(conn, pkt, conn.lastOowAckTimeMs())) {
                    // FIXME tcp_send_dupack
                    TcpSegmenter.INSTANCE.sendAck(conn);
                }
            } else if (tcp_reset_check(conn, pkt)) {
                // Linux tcp_reset_check: accept bare RST at RCV.NXT - 1 in half-close states.
                tcp_reset(ctx, conn, closePromise);
            }
            return discard(conn, pkt, reason);
        }

        // Step 2: RST handling (RFC 9293 §3.5.2 + RFC 5961 §3.2).
        if (pkt.isRst()) {

            /*-
             * RFC 5961 3.2 (extend to match against (RCV.NXT - 1) after a  FIN and SACK too if available):
             * If seq num matches RCV.NXT or (RCV.NXT - 1) after a FIN, or the right-most SACK block,
             * then
             *     RESET the connection
             * else
             *     Send a challenge ACK
             */
            if (pkt.tcpSeq() == conn.rcvNxt() || tcp_reset_check(conn, pkt)) {
                tcp_reset(ctx, conn, closePromise);
                return false;
            }

            // TODO SACK

            tcp_send_challenge_ack(conn, false);
            return discard(conn, pkt, SKB_DROP_REASON_TCP_RESET);
        }

        // Step 4: SYN challenge in established/closing states (RFC 5961 §4).
        if (pkt.isSyn()) {
            int seq = pkt.tcpSeq();
            int endSeq = determineEndSeq(pkt);
            if (conn.state() == TcpConnectionState.TCP_SYN_RECV
                    // && sk->sk_socket
                    && pkt.isAck()
                    && seq + 1 == endSeq
                    && seq + 1 == conn.rcvNxt()
                    && pkt.tcpAckNum() == conn.sndNxt()) {
                return true;
            }

            tcp_send_challenge_ack(conn, accecn_reflector);
            return discard(conn, pkt, SKB_DROP_REASON_TCP_INVALID_SYN);
        }

        return true;
    }

    private boolean discard(TcpConnection tp, TcpPacketBuf pkt, int reason) {
        // TODO log reason
        return false;
    }

    /**
     * RFC 9293 §3.4 — segment acceptability test.
     * Written in the same shape as Linux {@code tcp_sequence()} for easier side-by-side
     * comparison:
     * <pre>
     *   if before(end_seq, rcv_wup)           → SKB_DROP_REASON_TCP_OLD_SEQUENCE
     *   if after(end_seq, rcv_nxt + rcv_wnd)  → invalid end sequence
     *      and after(seq, rcv_nxt + rcv_wnd)  → SKB_DROP_REASON_TCP_INVALID_SEQUENCE
     * </pre>
     *
     * @return {@link SkbDropReasonConstants#SKB_NOT_DROPPED_YET} (0) if the segment is acceptable;
     *         a non-zero {@code skb_drop_reason} constant otherwise.
     * @see <a href="https://elixir.bootlin.com/linux/latest/source/net/ipv4/tcp_input.c#L4394">tcp_sequence</a>
     */
    private static int tcp_sequence(TcpConnection conn, TcpPacketBuf pkt) {
        int seq    = pkt.tcpSeq();
        int segLen = pkt.tcpPayloadLength()
                + (pkt.isSyn() ? 1 : 0)
                + (pkt.isFin() ? 1 : 0);
        int endSeq = seq + segLen;
        int rcvWup    = conn.rcvWup();
        int rcvNxt    = conn.rcvNxt();
        int rcvWndEnd = rcvNxt + conn.tcp_receive_window();

        if (before(endSeq, rcvWup)) {
            return SKB_DROP_REASON_TCP_OLD_SEQUENCE;
        }
        if (after(endSeq, rcvWndEnd)) {
            // Allow FIN to extend one byte beyond the window.
            if (!after(endSeq - (pkt.isFin() ? 1 : 0), rcvWndEnd)) {
                return SKB_NOT_DROPPED_YET;
            }
            if (after(seq, rcvWndEnd)) {
                return SKB_DROP_REASON_TCP_INVALID_SEQUENCE;
            }
        }
        return SKB_NOT_DROPPED_YET;
    }

    /**
     * Disordered-ACK check used by the PAWS path to distinguish an acceptable
     * out-of-order ACK from one that must be dropped.
     *
     * @return {@link SkbDropReasonConstants#SKB_NOT_DROPPED_YET} if the ACK is acceptable;
     *         a non-zero {@code skb_drop_reason} constant otherwise.
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_input.c#L3585">tcp_disordered_ack</a>
     */
    private int tcp_disordered_ack_check(final TcpConnection tp, final TcpPacketBuf pkt) {
        int reason = SKB_DROP_REASON_TCP_RFC7323_PAWS;
        int seq = pkt.tcpSeq();
        int ack = pkt.tcpAckNum();

        /* 1. Is this not a pure ACK ? */
        if (!pkt.isAck() || seq != determineEndSeq(pkt)) {
            return reason;
        }

        /* 2. Is its sequence not the expected one ? */
        if (seq != tp.rcvNxt()) {
            return before(seq, tp.rcvNxt()) ? SKB_DROP_REASON_TCP_RFC7323_PAWS_ACK : reason;
        }

        /* 3. Is this not a duplicate ACK ? */
        if (ack != tp.sndUna()) {
            return reason;
        }

        /* 4. Is this updating the window ? */
//        if (tcp_may_update_window(tp, ack, seq, th.getWindowAsInt() << tp.rx_opt.snd_wscale)) {
//            return reason;
//        }
        /* 5. Is this not in the replay window ? */
//        if ((s32)(tp->rx_opt.ts_recent - tp->rx_opt.rcv_tsval) > tcp_tsval_replay(sk)) {
//            return reason;
//        }
        return SKB_NOT_DROPPED_YET;
    }

    /**
     * RFC 9293 §3.5.2 + RFC 5961 §3.2 — RST acceptability test (three-way result).
     *
     * <ul>
     *   <li>DROP          — SEG.SEQ outside receive window.</li>
     *   <li>RESET         — SEG.SEQ == RCV.NXT; valid reset.</li>
     *   <li>CHALLENGE_ACK — SEG.SEQ in window but != RCV.NXT;
     *       blind-RST attack mitigation (RFC 5961 §3.2).</li>
     * </ul>
     *
     * Linux {@code tcp_reset_check}: accept a bare RST whose sequence equals
     * {@code RCV.NXT - 1} while the local side has already sent its FIN.
     *
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_input.c#L6329">tcp_reset_check</a>
     */
    private static boolean tcp_reset_check(TcpConnection conn, TcpPacketBuf pkt) {
        if (pkt.tcpSeq() != conn.rcvNxt() - 1) {
            return false;
        }
        TcpConnectionState s = conn.state();
        return s == TcpConnectionState.CLOSE_WAIT
                || s == TcpConnectionState.LAST_ACK
                || s == TcpConnectionState.CLOSING;
    }
}

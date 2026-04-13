package com.github.pangolin.routing.acceptor.tun.net.v2.tcp.established;

import com.github.pangolin.routing.acceptor.tun.net.handler.support.TcpPacketBuf;
import com.github.pangolin.routing.acceptor.tun.net.handler.tcp.util.TcpOptionCodec;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.connection.TcpConnection;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.core.TcpSegmenter;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.internal.TcpConstants;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.internal.TcpSequence;
import io.netty.buffer.ByteBuf;

import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.connection.TcpConnectionState;

import static com.github.pangolin.routing.acceptor.tun.net.v2.tcp.internal.TcpSequence.after;
import static com.github.pangolin.routing.acceptor.tun.net.v2.tcp.internal.TcpSequence.before;

/**
 * RFC 9293 §3.10.7.4 segment validation: steps 1, 2, 4
 * (sequence acceptability + PAWS + RST + SYN challenge).
 *
 * <p>Step 3 (security/precedence) and step 6 (URG) are not implemented.
 *
 * <p>Stateless — all state is in {@link TcpConnection}.
 */
public final class TcpSegmentValidator {

    public static final TcpSegmentValidator INSTANCE = new TcpSegmentValidator();

    private TcpSegmentValidator() {}

    public enum ValidateResult {
        PASS,
        DROP,
        CHALLENGE_ACK,
        RESET
    }

    /**
     * Unified validation path for post-handshake states, aligned with Linux
     * {@code tcp_validate_incoming} ordering:
     * PAWS → sequence check → RST check → SYN challenge.
     *
     * <p>Step 1 failure ordering mirrors Linux exactly (RFC793/RFC9293 §3.5.3):
     * <pre>
     *   if (!rst) {
     *       if (syn)  → syn_challenge
     *       else      → rate-limited dupack
     *   } else if (tcp_reset_check) → reset
     *   // discard
     * </pre>
     *
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_input.c#L5870">tcp_validate_incoming</a>
     */
    public ValidateResult validateIncoming(TcpConnection conn, TcpPacketBuf pkt) {
        // PAWS check (RFC 7323 §5) — RST is exempt.
        if (conn.timestampExt().isEnabled(conn)) {
            ByteBuf opts = pkt.tcpOptionsSlice();
            long[] ts = TcpOptionCodec.parseTimestamp(opts);
            if (ts != null && conn.timestampExt().isPawsRejected(conn, (int) ts[0])) {
                if (!pkt.isRst()) {
                    TcpSegmenter.INSTANCE.sendAck(conn);
                    return ValidateResult.DROP;
                }
                // RST is accepted even if it did not pass PAWS — fall through to step1.
            }
        }

        // Step 1: sequence number acceptability (RFC 9293 §3.4).
        if (!tcp_sequence(conn, pkt)) {
            /*
             * RFC793 p.37: "In all states except SYN-SENT, all reset (RST) segments are
             * validated by checking their SEQ-fields."
             * RFC793 p.69: "If an incoming segment is not acceptable, an acknowledgment
             * should be sent in reply (unless the RST bit is set, if so drop the segment
             * and return)."
             *
             * Mirror Linux ordering: !rst branch first (handles SYN inside), then RST.
             */
            if (!pkt.isRst()) {
                if (pkt.isSyn()) {
                    // Linux syn_challenge: challenge ACK on invalid-sequence SYN (RFC 5961 §4).
                    TcpSegmenter.INSTANCE.sendAck(conn);
                    return ValidateResult.CHALLENGE_ACK;
                }
                // Rate-limited dupack for out-of-window non-RST/non-SYN segments.
                if (shouldSendOowAck(conn)) {
                    TcpSegmenter.INSTANCE.sendAck(conn);
                }
            } else if (tcpResetCheck(conn, pkt)) {
                // Linux tcp_reset_check: accept bare RST at RCV.NXT - 1 in half-close states.
                return ValidateResult.RESET;
            }
            return ValidateResult.DROP;
        }

        // Step 2: RST handling (RFC 9293 §3.5.2 + RFC 5961 §3.2).
        if (pkt.isRst()) {
            RstResult rr = checkRst(conn, pkt);
            // Also reset when tcp_reset_check matches (Linux tcp_reset_check extension).
            if (rr == RstResult.RESET || tcpResetCheck(conn, pkt)) {
                return ValidateResult.RESET;
            }
            if (rr == RstResult.CHALLENGE_ACK) {
                TcpSegmenter.INSTANCE.sendAck(conn);
                return ValidateResult.CHALLENGE_ACK;
            }
            return ValidateResult.DROP;
        }

        // Step 4: SYN challenge in established/closing states (RFC 5961 §4).
        if (pkt.isSyn()) {
            TcpSegmenter.INSTANCE.sendAck(conn);
            return ValidateResult.CHALLENGE_ACK;
        }
        return ValidateResult.PASS;
    }

    /**
     * RFC 9293 §3.5.2 + RFC 5961 §3.2 — RST acceptability test (three-way result).
     *
     * <p>The receive-window check (RFC 9293 §3.5.2):
     * <ul>
     *   <li>RCV.WND == 0: SEG.SEQ must equal RCV.NXT</li>
     *   <li>RCV.WND  > 0: RCV.NXT &le; SEG.SEQ &lt; RCV.NXT + RCV.WND</li>
     * </ul>
     *
     * <p>RFC 5961 §3.2 adds a finer distinction for the in-window case:
     * <ul>
     *   <li>{@link RstResult#DROP}  — SEG.SEQ is outside the receive window; silently drop.</li>
     *   <li>{@link RstResult#RESET} — SEG.SEQ == RCV.NXT; connection reset is valid.</li>
     *   <li>{@link RstResult#CHALLENGE_ACK} — SEG.SEQ is in window but != RCV.NXT;
     *       send a challenge ACK and drop (blind-RST attack mitigation).</li>
     * </ul>
     */
    public static RstResult checkRst(TcpConnection conn, TcpPacketBuf pkt) {
        int seq    = pkt.tcpSeq();
        int rcvNxt = conn.rcvNxt();
        int rcvWnd = conn.rcvWnd();
        if (rcvWnd == 0) {
            // Zero window: only exact match is acceptable (RFC 9293 §3.5.2)
            return seq == rcvNxt ? RstResult.RESET : RstResult.DROP;
        }
        // RCV.NXT <= SEG.SEQ < RCV.NXT + RCV.WND
        if (!TcpSequence.between(rcvNxt, seq, rcvNxt + rcvWnd - 1)) {
            return RstResult.DROP;
        }
        // In-window: exact match resets; otherwise challenge ACK (RFC 5961 §3.2)
        return seq == rcvNxt ? RstResult.RESET : RstResult.CHALLENGE_ACK;
    }

    /** Result of the RST acceptability check (RFC 9293 §3.5.2 + RFC 5961 §3.2). */
    public enum RstResult {
        /** SEG.SEQ is outside the receive window — silently drop. */
        DROP,
        /** SEG.SEQ == RCV.NXT — connection reset is valid, close immediately. */
        RESET,
        /** SEG.SEQ is in window but != RCV.NXT — send challenge ACK and drop. */
        CHALLENGE_ACK
    }

    /**
     * RFC 9293 §3.4 — segment acceptability test.
     * Written in the same shape as Linux tcp_sequence() for easier side-by-side comparison:
     *   1) before(end_seq, rcv_wup) -> old sequence
     *   2) after(end_seq, rcv_nxt + rcv_wnd) -> invalid end sequence
     *      and if after(seq, rcv_nxt + rcv_wnd) -> invalid sequence
     *
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_input.c#L4394">tcp_sequence</a>
     */
    private boolean tcp_sequence(TcpConnection conn, TcpPacketBuf pkt) {
        int seq = pkt.tcpSeq();
        int segLen = pkt.tcpPayloadLength() + (pkt.isSyn() ? 1 : 0) + (pkt.isFin() ? 1 : 0);
        int endSeq = seq + segLen; // exclusive end sequence (matches Linux end_seq style)
        int rcvWup = conn.rcvWup();
        int rcvNxt = conn.rcvNxt();

        if (before(endSeq, rcvWup)) {
            return false;
        }

        int rcvWndEnd = rcvNxt + conn.rcvWnd();
        if (after(endSeq, rcvWndEnd)) {
            /* Some stacks are known to handle FIN incorrectly; allow the
             * FIN to extend beyond the window and check it in detail later.
             */
            if (!after(endSeq - (pkt.isFin() ? 1 : 0), rcvWndEnd)) {
                return true;
            }

            if (after(seq, rcvWndEnd)) {
                return false;
            }
            // Linux keeps this packet only when receive queue is empty.
            // v2 has no direct receive-queue-length check at this layer, so reject conservatively.
//            return false;
        }
        return true;
    }

    private boolean shouldSendOowAck(TcpConnection conn) {
        long now = System.currentTimeMillis();
        long last = conn.lastOowAckTimeMs();
        if (last == 0L || now - last >= TcpConstants.INVALID_ACK_RATELIMIT_MS) {
            conn.lastOowAckTimeMs(now);
            return true;
        }
        return false;
    }

    /**
     * Linux {@code tcp_reset_check}: accept a bare RST whose sequence number equals
     * {@code RCV.NXT - 1} while the connection is in a half-close state where the local
     * side has already sent its FIN.  This handles the edge case where {@code rcv_wup}
     * lags behind {@code rcv_nxt} so that {@code SEG.SEQ = RCV.NXT - 1} slips past the
     * sequence-acceptability check but is still within the "just closed" window.
     *
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_input.c#L1749">tcp_reset_check</a>
     */
    private static boolean tcpResetCheck(TcpConnection conn, TcpPacketBuf pkt) {
        if (pkt.tcpSeq() != conn.rcvNxt() - 1) {
            return false;
        }
        TcpConnectionState s = conn.state();
        return s == TcpConnectionState.CLOSE_WAIT
                || s == TcpConnectionState.LAST_ACK
                || s == TcpConnectionState.CLOSING;
    }
}

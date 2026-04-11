package com.github.pangolin.routing.acceptor.tun.net.v2.tcp.established;

import com.github.pangolin.routing.acceptor.tun.net.handler.support.TcpPacketBuf;
import com.github.pangolin.routing.acceptor.tun.net.handler.tcp.util.TcpOptionCodec;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.connection.TcpConnection;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.core.TcpSegmenter;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.internal.TcpSequence;
import io.netty.buffer.ByteBuf;

/**
 * RFC 9293 §3.10.7.4 segment validation: steps 1 + 4 (sequence + PAWS + SYN challenge).
 *
 * <p>Step 2 (RST) is handled by the caller before {@link #validate} is invoked.
 * Step 3 (security) and step 6 (URG) are not implemented.
 *
 * <p>Stateless — all state is in {@link TcpConnection}.
 */
public final class TcpSegmentValidator {

    public static final TcpSegmentValidator INSTANCE = new TcpSegmentValidator();

    private TcpSegmentValidator() {}

    /**
     * Validate an incoming segment (steps 1 + 4 of RFC 9293 §3.10.7.4).
     *
     * <p>Order:
     * <ol>
     *   <li>PAWS check (RFC 7323 §5) — must precede sequence check.</li>
     *   <li>Step 1: sequence number acceptability (RFC 9293 §3.4).
     *       Unacceptable segment: send ACK and drop.</li>
     *   <li>Step 4: SYN in established state — send challenge ACK and drop
     *       (RFC 5961 §4.2).  A SYN whose sequence falls inside the receive
     *       window would otherwise corrupt the connection.</li>
     * </ol>
     *
     * <p>Note: RST (step 2) is never passed here — callers handle it first.
     * PAWS does not apply to RST for the same reason.
     *
     * @return {@code true} if the segment is acceptable and processing should continue;
     *         {@code false} if it should be silently dropped or an ACK was sent
     */
    public boolean validate(TcpConnection conn, TcpPacketBuf pkt) {
        // PAWS check (RFC 7323 §5) — runs before sequence check.
        // RST never reaches here (handled by caller), so no RST exemption needed.
        if (conn.timestampExt().isEnabled(conn)) {
            ByteBuf opts = pkt.tcpOptionsSlice();
            long[] ts = TcpOptionCodec.parseTimestamp(opts);
            if (ts != null && conn.timestampExt().isPawsRejected(conn, (int) ts[0])) {
                // PAWS: send ACK and drop (RFC 7323 §5.1)
                TcpSegmenter.INSTANCE.sendAck(conn);
                return false;
            }
        }

        // Step 1: sequence number acceptability (RFC 9293 §3.4).
        // Unacceptable segment → send ACK (unless RST, but RST never reaches here).
        if (!isAcceptable(conn, pkt)) {
            TcpSegmenter.INSTANCE.sendAck(conn);
            return false;
        }

        // Step 4: SYN in established state (RFC 9293 §3.10.7.4 / RFC 5961 §4.2).
        // A SYN whose seq is inside the receive window is either a retransmit confusion
        // or a spoofed attack.  Send a challenge ACK and drop — do NOT reset.
        if (pkt.isSyn()) {
            TcpSegmenter.INSTANCE.sendAck(conn);   // challenge ACK
            return false;
        }

        return true;
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
     * A segment is acceptable if it overlaps the receive window.
     */
    private boolean isAcceptable(TcpConnection conn, TcpPacketBuf pkt) {
        int seq    = pkt.tcpSeq();
        int segLen = pkt.tcpPayloadLength() + (pkt.isSyn() ? 1 : 0) + (pkt.isFin() ? 1 : 0);

        if (segLen == 0) {
            if (conn.rcvWnd() == 0) {
                return seq == conn.rcvNxt();
            }
            return isInWindow(conn, seq);
        } else {
            if (conn.rcvWnd() == 0) {
                // RFC 793 / Linux special case: a bare FIN at RCV.NXT is acceptable
                // even at zero window — peer is signalling end-of-data without payload.
                // (mirrors tcp_data_queue: "Some stacks send bare FIN even if we send RWIN 0")
                return pkt.tcpPayloadLength() == 0 && pkt.isFin() && seq == conn.rcvNxt();
            }
            // At least one byte must be in window
            return isInWindow(conn, seq) || isInWindow(conn, seq + segLen - 1);
        }
    }

    private boolean isInWindow(TcpConnection conn, int seq) {
        // RFC 9293 §3.4: window is [RCV.NXT, RCV.NXT+RCV.WND) — exclusive upper bound.
        // TcpSequence.between() is inclusive on both ends, so subtract 1 to convert.
        int rcvNxt = conn.rcvNxt();
        return TcpSequence.between(rcvNxt, seq, rcvNxt + conn.rcvWnd() - 1);
    }
}

package com.github.pangolin.routing.acceptor.tun.net.v2.tcp.established;

import com.github.pangolin.routing.acceptor.tun.net.handler.support.TcpPacketBuf;
import com.github.pangolin.routing.acceptor.tun.net.handler.tcp.util.TcpOptionCodec;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.connection.TcpConnection;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.core.TcpSegmenter;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.internal.TcpSequence;
import io.netty.buffer.ByteBuf;

/**
 * RFC 9293 §3.4 segment validation: sequence number check + RST processing + PAWS (RFC 7323).
 * Stateless — all state is in {@link TcpConnection}.
 */
public final class TcpSegmentValidator {

    public static final TcpSegmentValidator INSTANCE = new TcpSegmentValidator();

    private TcpSegmentValidator() {}

    /**
     * Validate an incoming segment.
     *
     * @return {@code true} if the segment is acceptable and processing should continue;
     *         {@code false} if it should be silently dropped or a RST was sent
     */
    public boolean validate(TcpConnection conn, TcpPacketBuf pkt) {
        // PAWS check (RFC 7323 §5) — must run before sequence check
        if (conn.timestampExt().isEnabled(conn)) {
            // Parse TSval from the incoming segment's options
            ByteBuf opts = pkt.tcpOptionsSlice();
            long[] ts = TcpOptionCodec.parseTimestamp(opts);
            if (ts != null && conn.timestampExt().isPawsRejected(conn, (int) ts[0])) {
                // PAWS: send ACK and drop the segment (RFC 7323 §5.1)
                TcpSegmenter.INSTANCE.sendAck(conn);
                return false;
            }
        }

        // NOTE: RST is handled by callers before validate() is called.
        // validate() is never reached with an RST segment.

        // Sequence number acceptability (RFC 9293 §3.4)
        if (!isAcceptable(conn, pkt)) {
            if (!pkt.isRst()) {
                TcpSegmenter.INSTANCE.sendAck(conn);
            }
            return false;
        }

        return true;
    }

    /**
     * RFC 9293 §3.5.2 — RST acceptability test.
     *
     * <p>A RST is valid only when its sequence number falls in the receive window:
     * <ul>
     *   <li>RCV.WND == 0: SEG.SEQ must equal RCV.NXT (exact match)</li>
     *   <li>RCV.WND  > 0: RCV.NXT &le; SEG.SEQ &lt; RCV.NXT + RCV.WND</li>
     * </ul>
     */
    public static boolean isRstAcceptable(TcpConnection conn, TcpPacketBuf pkt) {
        int seq    = pkt.tcpSeq();
        int rcvNxt = conn.rcvNxt();
        int rcvWnd = conn.rcvWnd();
        if (rcvWnd == 0) {
            return seq == rcvNxt;
        }
        // RCV.NXT <= SEG.SEQ < RCV.NXT + RCV.WND  (exclude the upper bound)
        return TcpSequence.between(rcvNxt, seq, rcvNxt + rcvWnd - 1);
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

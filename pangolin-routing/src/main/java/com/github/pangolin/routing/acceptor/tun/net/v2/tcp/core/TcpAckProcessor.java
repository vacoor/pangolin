package com.github.pangolin.routing.acceptor.tun.net.v2.tcp.core;

import com.github.pangolin.routing.acceptor.tun.net.handler.support.TcpPacketBuf;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.connection.TcpConnection;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.connection.TcpSendBuffer.TcpSegmentEntry;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.internal.TcpSequence;

/**
 * RFC 9293 ACK processing (sequence number advancement, window update, RTT sampling).
 * Delegates to RFC 5681 (CC) and RFC 6298 (RTT) via pluggable interfaces.
 *
 * <p>Stateless: all state is held in {@link TcpConnection}.
 */
public final class TcpAckProcessor {

    public static final TcpAckProcessor INSTANCE = new TcpAckProcessor();

    private TcpAckProcessor() {}

    /**
     * Process an ACK segment against the connection state.
     *
     * <p>Steps:
     * <ol>
     *   <li>Advance SND.UNA</li>
     *   <li>Update SND.WND</li>
     *   <li>Sample RTT (Karn's algorithm)</li>
     *   <li>Notify CC (RFC 5681)</li>
     *   <li>Notify loss detector (RFC 8985)</li>
     * </ol>
     */
    public void onAck(TcpConnection conn, TcpPacketBuf pkt) {
        int ackSeq   = pkt.tcpAckNum();
        int prevUna  = conn.sndUna();

        // Ignore ACKs beyond SND.NXT (future ACKs are invalid)
        if (TcpSequence.after(ackSeq, conn.sndNxt())) {
            return;
        }

        int ackedBytes = conn.acknowledgeUpTo(ackSeq);
        boolean advanced = TcpSequence.after(conn.sndUna(), prevUna);

        // Update peer's receive window
        int newWnd = pkt.tcpWindow();
        if (conn.sndWscale() > 0) newWnd <<= conn.sndWscale();
        conn.sndWnd(newWnd);

        // Convert acknowledged bytes to segment count.
        // ⚠ ackedBytes / mss == 0 for the final small segment (ackedBytes < mss).
        //   When sndUnaAdvanced=true but ackedSegs=0, CC never grows cwnd.
        //   Fix: if any bytes were ACK'd, count at least 1 segment.
        int ackedSegs = ackedBytes > 0 ? Math.max(1, ackedBytes / conn.mss()) : 0;

        // RTT sample — Karn's algorithm: pass -1 for retransmitted segments.
        long rttUs = rttSample(conn, pkt);
        conn.rttEstimator().addSample(conn, rttUs);   // skips if rttUs < 0

        // Congestion control (RFC 5681)
        conn.congestionControl().onAck(conn, ackedSegs, advanced);

        // Loss detector (RFC 8985)
        conn.lossDetector().onAck(conn, ackSeq, 0);

        // Reset RTO backoff on new data ACK
        if (advanced) {
            conn.rttEstimator().resetBackoff(conn);
        }
    }

    // ── RTT sampling ──────────────────────────────────────────────────────

    /**
     * Estimate RTT from the ACK, applying Karn's algorithm.
     *
     * @return RTT in microseconds, or -1 to signal "skip" (Karn's rule: retransmitted segment)
     */
    private long rttSample(TcpConnection conn, TcpPacketBuf pkt) {
        // Walk the RTX queue to find the segment this ACK covers
        TcpSegmentEntry head = conn.sendBuffer().peekRtx();
        if (head == null) return -1;
        if (head.isRetransmitted()) return -1;  // Karn: don't sample retransmits
        if (!TcpSequence.after(pkt.tcpAckNum(), head.startSeq())) return -1;

        long nowUs = System.nanoTime() / 1_000L;
        return nowUs - head.sentTimeUs();
    }
}

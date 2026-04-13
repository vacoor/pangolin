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

        // Precondition: caller (TcpSegmentValidator) has already verified ack <= SND.NXT.
        int ackedBytes = conn.acknowledgeUpTo(ackSeq);
        boolean advanced = TcpSequence.after(conn.sndUna(), prevUna);

        // tcp_ack_update_window: guarded SND.WND update
        tcpAckUpdateWindow(conn, pkt, advanced);

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

    // ── Window update ────────────────────────────────────────────────────

    /**
     * Mirrors Linux {@code tcp_ack_update_window} + {@code tcp_may_update_window}.
     *
     * <p>A window update is accepted only when the incoming segment is at least as recent
     * as the last one that updated the window ({@code snd_wl1} guard).  Without this guard
     * a stale duplicate ACK can shrink {@code SND.WND} and stall the sender.
     *
     * <p>The three acceptance conditions mirror Linux exactly:
     * <ol>
     *   <li>{@code newDataAcked}: new data was acknowledged — the window carried by this ACK
     *       is always authoritative (≈ {@code after(ack, old_snd_una)}).</li>
     *   <li>{@code after(SEG.SEQ, snd_wl1)}: the segment is strictly newer than the last
     *       window-update segment.</li>
     *   <li>{@code SEG.SEQ == snd_wl1 && (new_wnd > snd_wnd || new_wnd == 0)}: same
     *       segment position, but window is larger (or zero to allow probing).</li>
     * </ol>
     *
     * @param newDataAcked {@code true} iff {@code SND.UNA} was advanced by this ACK
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_input.c#L3688">tcp_ack_update_window</a>
     */
    private static void tcpAckUpdateWindow(TcpConnection conn, TcpPacketBuf pkt,
                                            boolean newDataAcked) {
        int seg  = pkt.tcpSeq();
        int nwin = pkt.tcpWindow() << conn.sndWscale();
        // tcp_may_update_window
        if (newDataAcked
                || TcpSequence.after(seg, conn.sndWl1())
                || (seg == conn.sndWl1()
                    && (TcpSequence.before(conn.sndWnd(), nwin) || nwin == 0))) {
            conn.sndWl1(seg);   // tcp_update_wl(tp, ack_seq): snd_wl1 = SEG.SEQ
            conn.sndWnd(nwin);
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

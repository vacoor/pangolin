package com.github.pangolin.routing.acceptor.tun.net.v2.tcp.core;

import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.connection.TcpConnection;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.timer.TimerType;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.timer.TcpTimerScheduler;

/**
 * RFC 9293 retransmission: re-sends the oldest unacknowledged segment.
 * Called by:
 * <ul>
 *   <li>{@link com.github.pangolin.routing.acceptor.tun.net.v2.tcp.ext.cc.CongestionControl}
 *       on fast retransmit (3 dupACKs)</li>
 *   <li>The retransmit timer on RTO expiry</li>
 * </ul>
 */
public final class TcpRetransmitter {

    public static final TcpRetransmitter INSTANCE = new TcpRetransmitter();

    private TcpRetransmitter() {}

    /**
     * Retransmit the oldest unacknowledged segment.
     * Used as the {@code retransmitCallback} injected into {@code CongestionControl.init()}.
     */
    public void retransmit(TcpConnection conn) {
        TcpOutput.INSTANCE.tcp_retransmit_skb(conn);
    }

    /**
     * Handle RTO expiry:
     * <ol>
     *   <li>Notify CC (halve cwnd, enter LOSS)</li>
     *   <li>Backoff RTO (RFC 6298 §5.5)</li>
     *   <li>Retransmit the oldest segment</li>
     *   <li>Reschedule the retransmit timer</li>
     * </ol>
     */
    public void onTimeout(TcpConnection conn) {
        conn.congestionControl().onTimeout(conn);
        conn.rttEstimator().backoff(conn);
        retransmit(conn);
        scheduleRetransmit(conn);
    }

    /** Arm the retransmit timer for the current RTO. */
    public void scheduleRetransmit(TcpConnection conn) {
        long rtoMs = conn.rttEstimator().rtoMs(conn);
        TcpTimerScheduler.INSTANCE.scheduleWriteTimer(
                conn, TimerType.RETRANSMIT, rtoMs, () -> onTimeout(conn));
    }

    /** Cancel the retransmit timer (called when RTX queue becomes empty). */
    public void cancelRetransmit(TcpConnection conn) {
        TcpTimerScheduler.INSTANCE.cancelWriteTimer(conn);
    }

    /**
     * RFC 6298 §5.2/§5.3: rearm or cancel the retransmit timer after SND.UNA advances.
     * Mirrors Linux {@code tcp_rearm_rto()}.
     *
     * <ul>
     *   <li>§5.2: {@code packets_out == 0} — all outstanding data acknowledged → cancel timer.</li>
     *   <li>§5.3: {@code packets_out > 0} — partial ACK, data still in flight → restart timer.</li>
     * </ul>
     *
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_output.c">tcp_rearm_rto</a>
     */
    public void rearmRto(TcpConnection conn) {
        if (conn.packetsOut() == 0) {
            cancelRetransmit(conn);
        } else {
            scheduleRetransmit(conn);
        }
    }
}

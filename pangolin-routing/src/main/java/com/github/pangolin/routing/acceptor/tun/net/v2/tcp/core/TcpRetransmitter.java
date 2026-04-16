package com.github.pangolin.routing.acceptor.tun.net.v2.tcp.core;

import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.ng.TcpMultiplexer.TcpSock;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.timer.TimerType;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.timer.TcpTimerScheduler;

/**
 * RFC 9293 retransmission: re-sends the oldest unacknowledged segment.
 * Called by fast retransmit in the inlined ACK/congestion-control path and by the
 * retransmit timer on RTO expiry.
 */
public final class TcpRetransmitter {

    public static final TcpRetransmitter INSTANCE = new TcpRetransmitter();

    private TcpRetransmitter() {}

    /**
     * Retransmit the oldest unacknowledged segment.
     */
    public void retransmit(TcpSock sock) {
        TcpOutput.INSTANCE.tcp_retransmit_skb(sock);
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
    public void onTimeout(TcpSock sock) {
        if (sock == null || !sock.hasConnection()) {
            return;
        }
        sock.onTimeoutByCc();
        sock.backoffRto();
        retransmit(sock);
        scheduleRetransmit(sock);
    }

    /** Arm the retransmit timer for the current RTO. */
    public void scheduleRetransmit(TcpSock sock) {
        if (sock == null || !sock.hasConnection()) {
            return;
        }
        long rtoMs = sock.rtoMs();
        TcpTimerScheduler.INSTANCE.scheduleWriteTimer(
                sock, TimerType.RETRANSMIT, rtoMs, () -> onTimeout(sock));
    }

    /** Cancel the retransmit timer (called when RTX queue becomes empty). */
    public void cancelRetransmit(TcpSock sock) {
        TcpTimerScheduler.INSTANCE.cancelWriteTimer(sock);
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
    public void rearmRto(TcpSock sock) {
        if (sock == null) {
            return;
        }
        if (sock.packetsOut() == 0) {
            cancelRetransmit(sock);
        } else {
            scheduleRetransmit(sock);
        }
    }
}

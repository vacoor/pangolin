package com.github.pangolin.routing.acceptor.tun.net.v2.tcp.core;


import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.core.TimerType;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.core.TcpTimerScheduler;

/**
 * RFC 9293 retransmission: re-sends the oldest unacknowledged segment.
 * Called by fast retransmit in the inlined ACK/congestion-control path and by the
 * retransmit timer on RTO expiry.
 */
public final class TcpRetransmitter {

    public TcpRetransmitter() {}

    /**
     * Retransmit the oldest unacknowledged segment.
     */
    public void retransmit(TcpSock sock) {
        sock.sender().retransmitSkb();
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
        sock.sender().tlpHighSeq(0);
        sock.onTimeoutByCc();
        sock.sender().backoff();
        retransmit(sock);
        scheduleRetransmit(sock);
    }

    /** Arm the retransmit timer for the current RTO. */
    public void scheduleRetransmit(TcpSock sock) {
        scheduleRetransmit(sock, sock == null ? 0L : sock.rtoMs());
    }

    public void scheduleRetransmit(TcpSock sock, long delayMs) {
        if (sock == null || !sock.hasConnection()) {
            return;
        }
        TcpTimerScheduler.INSTANCE.scheduleWriteTimer(
                sock, TimerType.RETRANSMIT, Math.max(delayMs, 1L), () -> onTimeout(sock));
    }

    public void scheduleLossProbe(TcpSock sock, long delayMs) {
        if (sock == null || !sock.hasConnection()) {
            return;
        }
        TcpTimerScheduler.INSTANCE.rearmWriteTimerIfEarlier(
                sock, TimerType.TLP_PROBE, Math.max(delayMs, 1L), () -> onLossProbe(sock));
    }

    public void scheduleReorderTimeout(TcpSock sock, long delayMs) {
        if (sock == null || !sock.hasConnection()) {
            return;
        }
        TcpTimerScheduler.INSTANCE.rearmWriteTimerIfEarlier(
                sock, TimerType.REORDER_TIMEOUT, Math.max(delayMs, 1L), () -> onReorderTimeout(sock));
    }

    public void onLossProbe(TcpSock sock) {
        if (sock == null || !sock.hasConnection()) {
            return;
        }
        // writeXmit 栈帧内部若被 EmbeddedChannel.maybeRunPendingTasks 驱动 inline fire
        // 就 skip —— 外层 writeXmit 尾部的 scheduleLossProbe 会重新评估是否再武装
        // TLP。生产 NIO 下此分支恒不触发(NioEventLoop.writeAndFlush 不 inline 跑
        // scheduled tasks)。详见 Sender.xmitDepth 字段 javadoc。
        if (sock.sender().isInXmit()) {
            return;
        }
        sock.sender().sendLossProbe();
        scheduleRetransmit(sock);
    }

    public void onReorderTimeout(TcpSock sock) {
        if (sock == null || !sock.hasConnection()) {
            return;
        }
        // v1 当前也只保留了 REO timeout 分支入口，没有实质 RACK 处理。
    }

    /** Cancel the retransmit timer (called when RTX queue becomes empty). */
    public void cancelRetransmit(TcpSock sock) {
        if (sock != null) {
            sock.sender().tlpHighSeq(0);
        }
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
            long rtoMs = sock.rtoMs();
            if (sock.timers() != null) {
                TimerType type = sock.timers().writeTimerType;
                if (type == TimerType.REORDER_TIMEOUT || type == TimerType.TLP_PROBE) {
                    long remaining = sock.timers().writeTimerExpires - System.currentTimeMillis();
                    rtoMs = Math.max(remaining, 1L);
                }
            }
            scheduleRetransmit(sock, rtoMs);
        }
    }
}

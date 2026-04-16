package com.github.pangolin.routing.acceptor.tun.net.v2.tcp.timer;

import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.connection.TcpConnection;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.ng.TcpMultiplexer.TcpSock;

import java.util.concurrent.TimeUnit;

/**
 * Stateless timer scheduler for TCP connections.
 * Can be shared across connections; all scheduling uses the connection's own EventLoop,
 * which guarantees thread safety without locks.
 */
public final class TcpTimerScheduler {

    public static final TcpTimerScheduler INSTANCE = new TcpTimerScheduler();

    private TcpTimerScheduler() {}

    /**
     * Schedule or re-arm the write timer
     * (RETRANSMIT / TLP_PROBE / ZERO_WINDOW_PROBE / REORDER_TIMEOUT).
     * Cancels any existing write timer before scheduling.
     */
    public void scheduleWriteTimer(TcpConnection conn, TimerType type,
                                   long delayMs, Runnable action) {
        TcpConnectionTimers t = conn.timers();
        if (t.writeTimer != null && !t.writeTimer.isDone()) {
            t.writeTimer.cancel(false);
        }
        long delay           = Math.max(delayMs, 1L);
        t.writeTimerType     = type;
        t.writeTimerExpires  = System.currentTimeMillis() + delay;
        t.writeTimer         = conn.eventLoop().schedule(action, delay, TimeUnit.MILLISECONDS);
    }

    public void scheduleWriteTimer(TcpSock sock, TimerType type,
                                   long delayMs, Runnable action) {
        if (sock == null || sock.timers() == null || sock.eventLoop() == null) {
            return;
        }
        TcpConnectionTimers t = sock.timers();
        if (t.writeTimer != null && !t.writeTimer.isDone()) {
            t.writeTimer.cancel(false);
        }
        long delay = Math.max(delayMs, 1L);
        t.writeTimerType = type;
        t.writeTimerExpires = System.currentTimeMillis() + delay;
        t.writeTimer = sock.eventLoop().schedule(action, delay, TimeUnit.MILLISECONDS);
    }

    /** Cancel the write timer without replacing it. */
    public void cancelWriteTimer(TcpConnection conn) {
        TcpConnectionTimers t = conn.timers();
        if (t.writeTimer != null && !t.writeTimer.isDone()) {
            t.writeTimer.cancel(false);
        }
        t.writeTimer        = null;
        t.writeTimerType    = null;
        t.writeTimerExpires = 0L;
    }

    public void cancelWriteTimer(TcpSock sock) {
        if (sock == null || sock.timers() == null) {
            return;
        }
        TcpConnectionTimers t = sock.timers();
        if (t.writeTimer != null && !t.writeTimer.isDone()) {
            t.writeTimer.cancel(false);
        }
        t.writeTimer = null;
        t.writeTimerType = null;
        t.writeTimerExpires = 0L;
    }

    /**
     * Re-arm the write timer only if the new deadline is earlier than the current one.
     * Avoids unnecessary cancel/reschedule on every ACK.
     */
    public void rearmWriteTimerIfEarlier(TcpConnection conn, TimerType type,
                                          long delayMs, Runnable action) {
        TcpConnectionTimers t = conn.timers();
        long newExpiry = System.currentTimeMillis() + delayMs;
        if (t.writeTimer == null || t.writeTimer.isDone() || newExpiry < t.writeTimerExpires) {
            scheduleWriteTimer(conn, type, delayMs, action);
        }
    }

    public void rearmWriteTimerIfEarlier(TcpSock sock, TimerType type,
                                         long delayMs, Runnable action) {
        if (sock == null || sock.timers() == null) {
            return;
        }
        TcpConnectionTimers t = sock.timers();
        long newExpiry = System.currentTimeMillis() + delayMs;
        if (t.writeTimer == null || t.writeTimer.isDone() || newExpiry < t.writeTimerExpires) {
            scheduleWriteTimer(sock, type, delayMs, action);
        }
    }

    /** Schedule or re-arm the delayed ACK timer. */
    public void scheduleDelayedAck(TcpConnection conn, long delayMs, Runnable action) {
        TcpConnectionTimers t = conn.timers();
        if (t.delayedAckTimer != null && !t.delayedAckTimer.isDone()) {
            t.delayedAckTimer.cancel(false);
        }
        t.delayedAckTimer = conn.eventLoop()
                               .schedule(action, Math.max(delayMs, 1L), TimeUnit.MILLISECONDS);
    }

    public void scheduleDelayedAck(TcpSock sock, long delayMs, Runnable action) {
        if (sock == null || sock.timers() == null || sock.eventLoop() == null) {
            return;
        }
        TcpConnectionTimers t = sock.timers();
        if (t.delayedAckTimer != null && !t.delayedAckTimer.isDone()) {
            t.delayedAckTimer.cancel(false);
        }
        t.delayedAckTimer = sock.eventLoop()
                .schedule(action, Math.max(delayMs, 1L), TimeUnit.MILLISECONDS);
    }

    /** Cancel the delayed ACK timer. */
    public void cancelDelayedAck(TcpConnection conn) {
        TcpConnectionTimers t = conn.timers();
        if (t.delayedAckTimer != null && !t.delayedAckTimer.isDone()) {
            t.delayedAckTimer.cancel(false);
        }
        t.delayedAckTimer = null;
    }

    public void cancelDelayedAck(TcpSock sock) {
        if (sock == null || sock.timers() == null) {
            return;
        }
        TcpConnectionTimers t = sock.timers();
        if (t.delayedAckTimer != null && !t.delayedAckTimer.isDone()) {
            t.delayedAckTimer.cancel(false);
        }
        t.delayedAckTimer = null;
    }

    /** Schedule or re-arm the keepalive / FIN_WAIT_2 timer. */
    public void scheduleKeepalive(TcpConnection conn, long delayMs, Runnable action) {
        TcpConnectionTimers t = conn.timers();
        if (t.keepaliveTimer != null && !t.keepaliveTimer.isDone()) {
            t.keepaliveTimer.cancel(false);
        }
        t.keepaliveTimer = conn.eventLoop()
                              .schedule(action, Math.max(delayMs, 1L), TimeUnit.MILLISECONDS);
    }

    public void scheduleKeepalive(TcpSock sock, long delayMs, Runnable action) {
        if (sock == null || sock.timers() == null || sock.eventLoop() == null) {
            return;
        }
        TcpConnectionTimers t = sock.timers();
        if (t.keepaliveTimer != null && !t.keepaliveTimer.isDone()) {
            t.keepaliveTimer.cancel(false);
        }
        t.keepaliveTimer = sock.eventLoop()
                .schedule(action, Math.max(delayMs, 1L), TimeUnit.MILLISECONDS);
    }

    /** Cancel the keepalive / FIN_WAIT_2 timer. */
    public void cancelKeepalive(TcpConnection conn) {
        TcpConnectionTimers t = conn.timers();
        if (t.keepaliveTimer != null && !t.keepaliveTimer.isDone()) {
            t.keepaliveTimer.cancel(false);
        }
        t.keepaliveTimer = null;
    }

    public void cancelKeepalive(TcpSock sock) {
        if (sock == null || sock.timers() == null) {
            return;
        }
        TcpConnectionTimers t = sock.timers();
        if (t.keepaliveTimer != null && !t.keepaliveTimer.isDone()) {
            t.keepaliveTimer.cancel(false);
        }
        t.keepaliveTimer = null;
    }

    /** Cancel all timers for the connection. */
    public void cancelAll(TcpConnection conn) {
        conn.timers().cancelAll();
    }

    public void cancelAll(TcpSock sock) {
        if (sock == null || sock.timers() == null) {
            return;
        }
        sock.timers().cancelAll();
    }
}

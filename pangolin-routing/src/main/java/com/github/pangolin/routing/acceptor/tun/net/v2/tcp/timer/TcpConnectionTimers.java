package com.github.pangolin.routing.acceptor.tun.net.v2.tcp.timer;

import java.util.concurrent.ScheduledFuture;

/**
 * Per-connection timer slots.
 * <b>All fields must only be read/written from the connection's assigned Worker EventLoop.</b>
 * No synchronisation is required.
 *
 * <p>Slot assignment:
 * <ul>
 *   <li>{@code writeTimer} — RETRANSMIT / TLP_PROBE / ZERO_WINDOW_PROBE / REORDER_TIMEOUT</li>
 *   <li>{@code delayedAckTimer} — DELAYED_ACK</li>
 *   <li>{@code keepaliveTimer} — FIN_WAIT_2_TIMEOUT (matches Linux keepalive timer slot)</li>
 * </ul>
 */
public final class TcpConnectionTimers {

    /** Shared slot for write-path timers. */
    public ScheduledFuture<?> writeTimer;
    /** Which timer type currently occupies {@code writeTimer}. */
    public TimerType          writeTimerType;
    /** Absolute expiry in ms ({@code System.currentTimeMillis()}); used to avoid redundant re-arm. */
    public long               writeTimerExpires;

    /** Delayed-ACK timer slot. */
    public ScheduledFuture<?> delayedAckTimer;

    /** Keepalive / FIN_WAIT_2 timer slot. */
    public ScheduledFuture<?> keepaliveTimer;

    /** Cancel all active timers and reset every slot to {@code null}. */
    public void cancelAll() {
        cancel(writeTimer);
        cancel(delayedAckTimer);
        cancel(keepaliveTimer);
        writeTimer        = null;
        delayedAckTimer   = null;
        keepaliveTimer    = null;
        writeTimerType    = null;
        writeTimerExpires = 0L;
    }

    private static void cancel(ScheduledFuture<?> f) {
        if (f != null && !f.isDone()) {
            f.cancel(false);
        }
    }
}

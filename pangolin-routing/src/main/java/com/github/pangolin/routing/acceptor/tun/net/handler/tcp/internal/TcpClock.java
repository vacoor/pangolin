package com.github.pangolin.routing.acceptor.tun.net.handler.tcp.internal;

import java.util.concurrent.TimeUnit;

import static com.github.pangolin.routing.acceptor.tun.net.handler.tcp.internal.TcpConstants.HZ;

public class TcpClock {
    public static long tcp_clock_ns() {
        return System.nanoTime();
    }

    protected static long tcp_clock_us() {
        return TimeUnit.NANOSECONDS.toMicros(tcp_clock_ns());
    }

    protected static long tcp_clock_ms() {
        return TimeUnit.NANOSECONDS.toMillis(tcp_clock_ns());
    }

    public static long tcp_jiffies32() {
        return jiffies();
    }

    /*-
     * 定时器相关使用.
     */
    public static long jiffies() {
        return msecs_to_jiffies(TimeUnit.NANOSECONDS.toMillis(System.nanoTime()));
        // return msecs_to_jiffies(System.currentTimeMillis());
    }

    public static long usecs_to_jiffies(long us) {
        return msecs_to_jiffies(TimeUnit.MICROSECONDS.toMillis(us));
    }

    public static long nsecs_to_jiffies(long ns) {
        return msecs_to_jiffies(TimeUnit.NANOSECONDS.toMillis(ns));
    }

    public static long msecs_to_jiffies(long ms) {
        int MSEC_PER_SEC = 1000;
        if (0 == (HZ % MSEC_PER_SEC)) {
            return (HZ / MSEC_PER_SEC) * ms;
        }
        return (long) ((HZ * 1F / MSEC_PER_SEC) * ms);
    }

    public static long jiffies_to_usecs(long jiffies) {
        long USEC_PER_SEC = 1000 * 1000;
        if (0 == (USEC_PER_SEC % HZ)) {
            return USEC_PER_SEC / HZ * jiffies;
        }
        return (long) ((1000F * 1000 / HZ) * jiffies);
    }

    public static long jiffies_to_msecs(long jiffies) {
        return TimeUnit.MICROSECONDS.toMillis(jiffies_to_usecs(jiffies));
    }
}

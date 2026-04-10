package com.github.pangolin.routing.acceptor.tun.net.v2.tcp.internal;

/**
 * TCP protocol constants (RFC 9293 + Linux kernel sysctl defaults).
 */
public final class TcpConstants {

    private TcpConstants() {}

    // ---- Header sizes ----
    public static final int TCP_MIN_HEADER_LEN = 20;
    public static final int IP4_HEADER_LEN     = 20;

    // ---- MSS ----
    public static final int TCP_MSS_DEFAULT    = 536;
    public static final int TCP_MIN_MSS        = 88;
    public static final int TCP_MSS_1460       = 1460;  // typical Ethernet MSS

    // ---- Congestion control ----
    /** Initial congestion window segments (RFC 6928) */
    public static final int TCP_INIT_CWND      = 10;

    // ---- Window scaling ----
    public static final int TCP_MAX_WSCALE     = 14;

    // ---- RTO (RFC 6298) ----
    /** Initial RTO: 1 s (RFC 6298 §2.1) */
    public static final long RTO_INIT_MS       = 1_000L;
    /** Maximum RTO: 60 s */
    public static final long RTO_MAX_MS        = 60_000L;
    /** Minimum RTO: 200 ms (RFC 6298 §2.4) */
    public static final long RTO_MIN_MS        = 200L;

    // ---- TIME_WAIT ----
    /** 2×MSL = 60 s (Linux: TCP_TIMEWAIT_LEN) */
    public static final long TIME_WAIT_MS      = 60_000L;

    // ---- FIN_WAIT_2 ----
    /** FIN_WAIT_2 timeout; cf. Linux tcp_fin_timeout sysctl (default 60 s) */
    public static final long FIN_WAIT_2_TIMEOUT_MS = 60_000L;

    // ---- Delayed ACK ----
    /** Maximum delayed ACK delay: 40 ms (RFC 9293 §3.8.6.2.2) */
    public static final long DELAYED_ACK_MS    = 40L;

    // ---- ACK-pending bitmask flags (≈ Linux ICSK_ACK_* in inet_connection_sock.h) ----
    /** ACK is owed to the peer — set when data is received, cleared when ACK is sent. */
    public static final int ACK_SCHED = 0x01;   // ≈ ICSK_ACK_SCHED
    /** Delayed-ACK timer is armed. */
    public static final int ACK_TIMER = 0x02;   // ≈ ICSK_ACK_TIMER
    /** Send ACK immediately without delay (e.g. quickack mode). */
    public static final int ACK_NOW   = 0x10;   // ≈ ICSK_ACK_NOW

    // ---- Buffer sizes ----
    public static final int  TCP_DEFAULT_RCV_BUF = 87380;

    // ---- Misc ----
    public static final int  U16_MAX            = 65535;
    public static final int  TCP_MAX_WINDOW     = U16_MAX;
}

package com.github.pangolin.routing.acceptor.tun.net.v2.tcp.internal;

/**
 * TCP protocol constants (RFC 9293 + Linux kernel sysctl defaults).
 */
public final class TcpConstants {

    private TcpConstants() {}

    // ---- TCP header flag bits (≈ Linux include/linux/tcp.h TCPHDR_*) ----
    /** FIN — end of byte stream; consumes one sequence number. */
    public static final int TCPHDR_FIN = 0x01;
    /** SYN — synchronise sequence numbers; consumes one sequence number. */
    public static final int TCPHDR_SYN = 0x02;
    /** RST — reset the connection; sent immediately, does NOT consume a sequence number. */
    public static final int TCPHDR_RST = 0x04;
    /** PSH — push buffered data to the application immediately. */
    public static final int TCPHDR_PSH = 0x08;
    /** ACK — acknowledgement field is significant. */
    public static final int TCPHDR_ACK = 0x10;
    /** URG — urgent pointer field is significant (not implemented). */
    public static final int TCPHDR_URG = 0x20;
    /** ECE — ECN-Echo (RFC 3168). */
    public static final int TCPHDR_ECE = 0x40;
    /** CWR — congestion window reduced (RFC 3168). */
    public static final int TCPHDR_CWR = 0x80;

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
    /** Minimum delayed-ACK / persist base interval counterpart (Linux HZ/25). */
    public static final long TCP_ATO_MIN_MS = 40L;
    /** Resource pressure probe interval (Linux TCP_RESOURCE_PROBE_INTERVAL). */
    public static final long TCP_RESOURCE_PROBE_INTERVAL_MS = 500L;
    /** Linux tcp_retries2 default. */
    public static final int TCP_RETRIES2 = 15;
    /** Linux tcp_keepalive_probes default. */
    public static final int TCP_KEEPALIVE_PROBES = 9;
    /** Linux tcp_keepalive_time default. */
    public static final long TCP_KEEPALIVE_TIME_MS = 7_200_000L;
    /** Linux tcp_keepalive_intvl default. */
    public static final long TCP_KEEPALIVE_INTVL_MS = 75_000L;
    /** Linux tcp_pingpong_thresh default. */
    public static final int TCP_PINGPONG_THRESH = 1;
    /** Linux TCP_MAX_QUICKACKS. */
    public static final int TCP_MAX_QUICKACKS = 16;

    // ---- Delayed ACK ----
    /** Maximum delayed ACK delay: 40 ms (RFC 9293 §3.8.6.2.2) */
    public static final long DELAYED_ACK_MS    = 40L;
    /** Out-of-window invalid segment ACK rate-limit interval (~Linux HZ/2). */
    public static final long INVALID_ACK_RATELIMIT_MS = 500L;

    // ---- Nagle algorithm flags (≈ Linux TCP_NAGLE_* in include/net/tcp.h) ----
    /** Nagle algorithm disabled (TCP_NODELAY). */
    public static final int TCP_NAGLE_OFF   = 2;
    /** Force-push pending frames regardless of Nagle. */
    public static final int TCP_NAGLE_PUSH  = 4;
    /** TCP_CORK: hold data until cork is released. */
    public static final int TCP_NAGLE_CORK  = 8;

    // ---- ACK-pending bitmask flags (≈ Linux ICSK_ACK_* in inet_connection_sock.h) ----
    /** ACK is owed to the peer — set when data is received, cleared when ACK is sent. */
    public static final int ACK_SCHED = 0x01;   // ≈ ICSK_ACK_SCHED
    /** Delayed-ACK timer is armed. */
    public static final int ACK_TIMER = 0x02;   // ≈ ICSK_ACK_TIMER
    /** Send ACK immediately without delay (e.g. quickack mode). */
    public static final int ACK_NOW   = 0x10;   // ≈ ICSK_ACK_NOW

    // ---- sk_shutdown bitmask (Linux-style) ----
    /** Read side closed (peer sent FIN/RST). */
    public static final int RCV_SHUTDOWN  = 1;
    /** Write side closed (local sent FIN/RST). */
    public static final int SEND_SHUTDOWN = 2;
    /** Both directions closed. */
    public static final int SHUTDOWN_MASK = RCV_SHUTDOWN | SEND_SHUTDOWN;

    // ---- Buffer sizes ----
    public static final int  TCP_DEFAULT_RCV_BUF = 87380;

    // ---- Misc ----
    public static final int  U16_MAX            = 65535;
    public static final int  TCP_MAX_WINDOW     = U16_MAX;
}

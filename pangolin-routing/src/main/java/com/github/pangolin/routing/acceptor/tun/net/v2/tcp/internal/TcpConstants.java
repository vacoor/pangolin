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

    // ---- TCP_SKB_CB(skb)->sacked 位集(对齐 Linux include/net/tcp.h) ----
    /** 段已被 SACK 块确认 — 对应 Linux {@code TCPCB_SACKED_ACKED}。 */
    public static final int TCPCB_SACKED_ACKED   = 0x01;
    /** 段已被重传 — 对应 Linux {@code TCPCB_SACKED_RETRANS}。 */
    public static final int TCPCB_SACKED_RETRANS = 0x02;
    /** 段被判定为丢失 — 对应 Linux {@code TCPCB_LOST}。 */
    public static final int TCPCB_LOST           = 0x04;
    /** SACK 状态标志位掩码(ACKED | RETRANS | LOST) — 对应 Linux {@code TCPCB_TAGBITS}。 */
    public static final int TCPCB_TAGBITS        = 0x07;
    /** 段被 tcp_repair 修复(不计 skb_mstamp_ns)— 对应 Linux {@code TCPCB_REPAIRED}。 */
    public static final int TCPCB_REPAIRED       = 0x10;
    /** 段历史上被重传过(首次重传后永不清零)— 对应 Linux {@code TCPCB_EVER_RETRANS}。 */
    public static final int TCPCB_EVER_RETRANS   = 0x80;

    // ---- Header sizes ----
    public static final int TCP_MIN_HEADER_LEN = 20;
    public static final int IP4_HEADER_LEN     = 20;
    /**
     * 已协商 TSopt 时段内占用的字节(2-byte NOP pad + 10-byte TSopt)。
     * 对应 Linux {@code TCPOLEN_TSTAMP_ALIGNED} (include/net/tcp.h)。
     */
    public static final int TCP_TSOPT_WIRE_LEN = 12;

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
    /** Maximum RTO: 120 s (Linux TCP_RTO_MAX = 120*HZ) */
    public static final long RTO_MAX_MS        = 120_000L;
    /** Minimum RTO: 200 ms (RFC 6298 §2.4, Linux TCP_RTO_MIN = HZ/5) */
    public static final long RTO_MIN_MS        = 200L;

    // ---- TIME_WAIT ----
    /** 2×MSL = 60 s (Linux: TCP_TIMEWAIT_LEN) */
    public static final long TIME_WAIT_MS      = 60_000L;

    // ---- FIN_WAIT_2 ----
    /** FIN_WAIT_2 timeout; cf. Linux tcp_fin_timeout sysctl (default 60 s) */
    public static final long FIN_WAIT_2_TIMEOUT_MS = 60_000L;
    /** Minimum delayed-ACK / persist base interval counterpart (Linux HZ/25). */
    public static final long TCP_ATO_MIN_MS = 40L;
    /** Minimum delayed-ACK timeout (Linux TCP_DELACK_MIN = HZ/25). */
    public static final long TCP_DELACK_MIN_MS = 40L;
    /** Maximum delayed-ACK timeout (Linux TCP_DELACK_MAX = HZ/5). */
    public static final long TCP_DELACK_MAX_MS = 200L;
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

    // ---- PAWS / Timestamps (RFC 7323 §5) ----
    /**
     * PAWS 允许 TSval 与 ts_recent 之间的最大回绕容差 (Linux TCP_PAWS_WINDOW)。
     * 若 {@code ts_recent - rcv_tsval <= TCP_PAWS_WINDOW} 则视作可接受。
     */
    public static final int TCP_PAWS_WINDOW = 1;
    /** ts_recent 保活时长 (Linux TCP_PAWS_24DAYS),超过视为过期,重新接受任意 TSval。 */
    public static final long TCP_PAWS_24DAYS_SEC = 60L * 60L * 24L * 24L;
    /** TIME_WAIT 阶段 ts_recent 最短生存时间 (Linux TCP_PAWS_MSL,60 秒)。 */
    public static final int TCP_PAWS_MSL_SEC = 60;

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

    // ---- tcp_win_from_space / 内存压力 ----
    /**
     * {@code tcp_win_from_space} 缩放因子定点位数 — 对齐 Linux
     * {@code TCP_RMEM_TO_WIN_SCALE} (8) / {@code tcp_win_from_space}:
     * {@code win = (space * scaling_ratio) >> 8}。
     */
    public static final int  TCP_RMEM_TO_WIN_SCALE_SHIFT = 8;
    /**
     * 默认 {@code scaling_ratio} — Linux 初始值 {@code DIV_ROUND_UP(2 * TCP_MIN_RCVMSS, TCP_MSS_DEFAULT) << 8},
     * 折算 ≈ 205(即 ≈ 0.8)。v2 取静态常量,未接入 {@code tcp_update_scaling_ratio} 自适应。
     */
    public static final int  TCP_DEFAULT_SCALING_RATIO   = 205;
    /**
     * 内存压力阈值(字节)— 对齐 Linux {@code sysctl_tcp_mem[1]} (pressure)。
     * 全局 {@code tcp_memory_allocated > TCP_MEM_PRESSURE} 时 {@code tcp_under_memory_pressure}
     * 返回 true,{@code selectAdvertisedWindow} 会 clamp {@code rcv_ssthresh}。
     */
    public static final long TCP_MEM_PRESSURE = 64L * 1024 * 1024;  // 64 MiB
    /** 压力下 {@code rcv_ssthresh} clamp 上限 — 对齐 Linux {@code tcp_clamp_window} 的 {@code 4*advmss} 底线。 */
    public static final int  TCP_PRESSURE_RCV_SSTHRESH_MSS_MULT = 4;

    // ---- Misc ----
    public static final int  U16_MAX            = 65535;
    public static final int  TCP_MAX_WINDOW     = U16_MAX;
}

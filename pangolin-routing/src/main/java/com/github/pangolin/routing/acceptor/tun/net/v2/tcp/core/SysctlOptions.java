package com.github.pangolin.routing.acceptor.tun.net.v2.tcp.core;

/**
 * TCP 全局 sysctl 参数集中管理。
 *
 * <p>对齐 Linux 内核 {@code /proc/sys/net/ipv4/tcp_*} 与 {@code struct netns_ipv4} 中的
 * {@code sysctl_tcp_*} 字段,语义与命名严格同构 v1
 * {@code com.github.pangolin.routing.acceptor.tun.net.handler.tcp.internal.SysctlOptions}。
 *
 * <p>单位约定: v2 统一使用毫秒 (ms) 作为时间单位; v1 使用 jiffies (HZ=1000 时 1 jiffy = 1 ms),
 * 因此数值可直接等价换算。
 *
 * <p>分层说明(见 Linux include/net/net_namespace.h):
 * <ul>
 *   <li>系统级(本类): 所有 socket 共享的默认值,可全局调整</li>
 *   <li>连接级(TcpSock 实例字段): 初始化时从本类继承,可 per-socket 覆盖</li>
 * </ul>
 */
public final class SysctlOptions {

    private SysctlOptions() {}

    // ---- 通用 socket 接收缓冲区上限 ----
    public static int sysctl_rmem_max;

    // ---- 窗口缩放 / Nagle 等协商选项 ----
    /** 是否启用窗口缩放 (RFC 7323)。 */
    public static boolean ipv4_sysctl_tcp_window_scaling = true;
    /** pingpong 计数阈值 (切换到 delayed ACK 策略的判定)。 */
    public static int ipv4_sysctl_tcp_pingpong_thresh = 1;
    /** 是否按有符号 16 位窗口进行防御性缩减。 */
    public static boolean ipv4_sysctl_tcp_workaround_signed_windows;
    /** 是否允许接收窗口收缩。 */
    public static boolean ipv4_sysctl_tcp_shrink_window;

    // ---- 重试次数 ----
    /** 主动发起 SYN 的最大重试次数。 */
    public static int ipv4_sysctl_tcp_syn_retries = 5;
    /** 被动发送 SYN-ACK 的最大重试次数。 */
    public static int ipv4_sysctl_tcp_synack_retries = 5;
    /** 数据重传进入 "报告告警" 的阈值 (tcp_retries1)。 */
    public static int ipv4_sysctl_tcp_retries1 = 5;
    /** 数据重传放弃连接的阈值 (tcp_retries2)。 */
    public static int ipv4_sysctl_tcp_retries2 = 15;

    // ---- 缓冲 / MSS ----
    public static int ipv4_sysctl_tcp_rmem_2;
    public static int ipv4_sysctl_tcp_min_snd_mss;

    // ---- Keepalive ----
    /** keepalive 探测失败允许的最大次数。 */
    public static int ipv4_sysctl_tcp_keepalive_probes = 9;
    /** keepalive 空闲触发时间 (ms) — Linux 默认 2 小时。 */
    public static int ipv4_sysctl_tcp_keepalive_time = 7_200_000;
    /** keepalive 探测包间隔 (ms) — Linux 默认 75 秒。 */
    public static int ipv4_sysctl_tcp_keepalive_intvl = 75_000;

    // ---- FIN / TIME_WAIT ----
    /** FIN_WAIT_2 / linger2 默认超时 (ms) — Linux 默认 60 秒。 */
    public static int ipv4_sysctl_tcp_fin_timeout = 60_000;
    /**
     * 允许复用 TIME_WAIT 套接字。0 = 禁用, 1 = 仅 loopback, 2 = 全部启用。
     * 需 timestamps 开启才生效。
     */
    public static int ipv4_sysctl_tcp_tw_reuse = 0;

    // ---- 速率限制 / 挑战 ACK ----
    /**
     * 无效包 (OOW/RST 等) 触发挑战 ACK 的速率限制间隔 (ms) — Linux HZ/2 = 500ms。
     */
    public static int sysctl_tcp_invalid_ratelimit = 500;
    /**
     * 每秒允许发送的挑战 ACK 总数 (RFC 5961) — Linux HZ = 1000。
     */
    public static int sysctl_tcp_challenge_ack_limit = 1000;

    // ---- 乱序接收 / 时间戳 ----
    /**
     * 是否启用乱序 (OFO) 队列。false 时乱序段直接丢弃。
     */
    public static boolean sysctl_tcp_ofo_enabled = true;
    /**
     * 是否协商 TCP Timestamps 选项 (RFC 7323)。
     */
    public static boolean ipv4_sysctl_tcp_timestamps = true;

    // ---- 空闲后慢启动 (RFC 5681 §4.1) ----
    /**
     * 空闲时长超过 RTO 后下一次发送前是否把 {@code snd_cwnd} 回退到
     * {@link TcpConstants#TCP_INIT_CWND} 上限之内 — 对齐 Linux
     * {@code sysctl_tcp_slow_start_after_idle}(默认开启)。
     *
     * <p>关闭后 idle 恢复发包时直接使用历史 {@code snd_cwnd},易在路径已变差时
     * 触发 burst loss;开启可与 {@code tcp_cwnd_validate} 配合,给应用层恢复
     * 发送一个保守 ACK clock。
     */
    public static boolean ipv4_sysctl_tcp_slow_start_after_idle = true;

    // ---- 内存压力统计 (Linux tcp_memory_allocated / sysctl_tcp_mem) ----
    /**
     * 全局 TCP 占用字节数 — 对齐 Linux {@code tcp_memory_allocated} /
     * {@code atomic_long_t}。每个 {@code TcpReceiveBuffer} 的加减操作通过
     * {@link #tcp_mem_delta} 汇总到此处,供 {@link #underMemoryPressure()} 判定。
     */
    public static final java.util.concurrent.atomic.AtomicLong tcp_memory_allocated =
            new java.util.concurrent.atomic.AtomicLong();
    /**
     * 压力判定阈值(字节)— 对齐 Linux {@code sysctl_tcp_mem[1]}。可由运行期调参改写;
     * 默认值取 {@link TcpConstants#TCP_MEM_PRESSURE}。
     */
    public static long ipv4_sysctl_tcp_mem_pressure = TcpConstants.TCP_MEM_PRESSURE;

    /**
     * 全局 rmem delta 钩子:由 {@code TcpReceiveBuffer} 在 OFO / in-order 字节增减时回调,
     * 对齐 Linux {@code sk_memory_allocated_add/sub}。
     */
    public static final java.util.function.IntConsumer tcp_mem_delta = delta -> {
        if (delta != 0) {
            tcp_memory_allocated.addAndGet(delta);
        }
    };

    /**
     * 对齐 Linux {@code underMemoryPressure(sk)}:当前累计占用超过
     * {@link #ipv4_sysctl_tcp_mem_pressure} 即视为压力,调用方据此 clamp
     * {@code rcv_ssthresh}、触发 {@code tcp_prune_queue} 等退路。
     */
    public static boolean underMemoryPressure() {
        return tcp_memory_allocated.get() > ipv4_sysctl_tcp_mem_pressure;
    }
}

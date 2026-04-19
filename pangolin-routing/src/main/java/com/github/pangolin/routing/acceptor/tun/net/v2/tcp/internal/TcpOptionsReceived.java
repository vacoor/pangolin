package com.github.pangolin.routing.acceptor.tun.net.v2.tcp.internal;

/**
 * 解析入站 TCP 选项后的结果集合。
 *
 * <p>对齐 Linux 内核 {@code struct tcp_options_received} (include/linux/tcp.h):
 * RFC 7323 Timestamps / Window Scaling、RFC 2018 SACK、用户 MSS 协商结果均落在此。
 *
 * <p>v2 沿用 v1 {@link com.github.pangolin.routing.acceptor.tun.net.handler.tcp.internal.tcp_options_received}
 * 的字段命名,便于与 Linux 源码 / v1 代码逐行比对。
 *
 * @see <a href="https://github.com/torvalds/linux/blob/master/include/linux/tcp.h#L111">struct tcp_options_received</a>
 */
public class TcpOptionsReceived {
    /** Timestamp 选项中对端最近一次有效 TSval 的本地接收时间戳 (jiffies / ms)。 */
    public int ts_recent_stamp;
    /** PAWS 使用的对端 TSval 缓存 (RFC 7323)。 */
    public long ts_recent;
    /** 本段携带的 TSval。 */
    public long rcv_tsval;
    /** 本段携带的 TSecr。 */
    public long rcv_tsecr;
    /** 本段是否观察到 Timestamp 选项。 */
    public long saw_tstmap;
    /** SYN/SYN-ACK 协商 Timestamps 是否成功 (对应 tp->rx_opt.tstamp_ok)。 */
    public boolean tstamp_ok;
    /** DSACK 相关标志 (RFC 2883)。 */
    public int dsack;
    /** SYN/SYN-ACK 协商 Window Scaling 是否成功。 */
    public boolean wscale_ok;
    /** SYN/SYN-ACK 协商 SACK 是否成功。 */
    public int sack_ok;
    /** SMC 扩展是否已协商 (未使用,保留与 Linux 对齐)。 */
    public int smc_ok;
    /** 对端接收窗口缩放因子 (本端发送方向)。 */
    public int snd_wscale;
    /** 本端接收窗口缩放因子 (本端接收方向)。 */
    public int rcv_wscale;
    /** 是否遇到未知 TCP 选项 (用于告警 / 统计)。 */
    public int saw_unknown;
    /** 与 Linux 对齐的保留位。 */
    public int unused;
    /** 已解析的 SACK 块数量。 */
    public int num_sacks;
    /** 用户通过 setsockopt(TCP_MAXSEG) 设置的 MSS。 */
    public int user_mss;
    /** 对端在 SYN 中通告的 MSS 选项值(受路径 MTU 夹制)。 */
    public int mss_clamp;
}

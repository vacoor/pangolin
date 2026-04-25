package com.github.pangolin.routing.acceptor.tun.net.v2.tcp.cc;

/**
 * 拥塞控制 SPI 的 ACK 输入样本。每个 ACK 处理后由 {@code Sender} 聚合一次,
 * 通过 {@link TcpCongestionControl#onAck} 传给算法实现。
 *
 * <p>对齐 Linux {@code struct rate_sample}(net/ipv4/tcp_rate.c)。同时承载
 * NewReno / CUBIC(丢包驱动)与 BBR(模型驱动)所需信息;算法实现忽略不需要的
 * 字段,无成本。
 *
 * <p><b>线程模型与生命周期</b>:{@code Sender} 持有单例 scratch 对象,每次 ACK
 * 处理前调用 {@link #reset()} 重置,然后填充字段并调 {@code onAck}。算法实现
 * <b>不允许</b>在 {@code onAck} 之外保留 {@code RateSample} 引用 — 下次 ACK 会
 * 覆盖。
 *
 * <p>字段分组:
 * <ul>
 *   <li><b>通用</b>(NewReno / CUBIC / BBR 都用):
 *       {@link #ackedPackets} / {@link #ackedBytes} / {@link #rttUs} /
 *       {@link #appLimited} / {@link #ecnMarked}</li>
 *   <li><b>BBR / 部分 Linux CC 用</b>:
 *       {@link #delivered} / {@link #priorDelivered} / {@link #sendElapsedUs} /
 *       {@link #ackElapsedUs} / {@link #isAckedRetrans} / {@link #minRttUs}</li>
 * </ul>
 */
public final class RateSample {

    // ── 通用字段 ───────────────────────────────────────────────

    /** 本次 ACK 释放的段数。 */
    public int ackedPackets;
    /** 本次 ACK 释放的字节数。 */
    public int ackedBytes;
    /**
     * 本次 ACK 的 RTT 样本(微秒)。0 表示无效样本(例如本次释放的段被重传过 —
     * Karn's algorithm 拒绝该样本)。
     */
    public long rttUs;
    /** 发送时刻 sender 是否 application-limited(写队列空 / cwnd 未用满)。 */
    public boolean appLimited;
    /** 本次 ACK 携带 ECE(对端反馈拥塞)。 */
    public boolean ecnMarked;

    // ── BBR / 模型驱动算法专用 ─────────────────────────────────

    /**
     * {@code tp->delivered} 在本次 ACK 处理后的累计投递字节数。BBR 用于计算
     * {@code delivery_rate = (delivered - priorDelivered) / interval}。
     */
    public int delivered;
    /**
     * 本次 ACK 释放的最旧段的 {@code TcpSegment.txDelivered} 快照(段发送时的
     * {@code tp->delivered})。
     */
    public int priorDelivered;
    /** {@code nowUs - firstAckedSeg.sentTimeUs}(BBR 的 send-side 采样区间)。 */
    public long sendElapsedUs;
    /**
     * {@code nowUs - priorDeliveredMstamp}(BBR 的 ack-side 采样区间)。
     * BBR 取 {@code max(sendElapsedUs, ackElapsedUs)} 作为最终 interval。
     */
    public long ackElapsedUs;
    /** 本次 ACK 释放的段含重传段 → BDP 估计应该排除该样本(Karn's algorithm)。 */
    public boolean isAckedRetrans;
    /** 最近 10 秒滑窗的最小 RTT(BBR / Vegas 用作 min_rtt 估计)。 */
    public long minRttUs;

    /**
     * 重置所有字段为零值。{@code Sender} 在每次 ACK 处理前调用,然后填充字段
     * 再调 {@link TcpCongestionControl#onAck}。
     */
    public void reset() {
        ackedPackets = 0;
        ackedBytes = 0;
        rttUs = 0L;
        appLimited = false;
        ecnMarked = false;
        delivered = 0;
        priorDelivered = 0;
        sendElapsedUs = 0L;
        ackElapsedUs = 0L;
        isAckedRetrans = false;
        minRttUs = 0L;
    }
}

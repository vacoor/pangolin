package com.github.pangolin.routing.acceptor.tun.net.v2.tcp.cc;

import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.core.TcpSock;

/**
 * 拥塞控制算法 SPI — v2 TCP 唯一稳定 SPI。
 *
 * <p>对齐 Linux {@code tcp_congestion_ops}(include/net/tcp.h),但**统一为单
 * {@link #onAck} 入口**,不分裂为 {@code cong_avoid} + {@code cong_control}
 * 两条历史路径。NewReno / CUBIC(丢包驱动)与 BBR(模型驱动)以及未来的
 * Vegas / Westwood / DCTCP / Compound TCP 等都通过同一接口落地;不同算法读取
 * {@link RateSample} 的不同子集,无成本忽略不需要的字段。
 *
 * <p><b>禁止事项</b>(对齐 Linux 设计原则):
 * <ol>
 *   <li>不解析 SACK block — SACK tagging 由 core 模块负责</li>
 *   <li>不遍历 RTX queue — 选段在 {@code RetransmissionSelector}</li>
 *   <li>不设置段标记位({@code TCPCB_LOST/RETRANS/SACKED_ACKED})</li>
 *   <li>不负责 TLP / RTO timer — 由 {@code TlpController} / {@code TcpRetransmitter}</li>
 *   <li>不持有 {@link TcpSock} 引用之外的 mutable 状态(算法私有状态除外)</li>
 * </ol>
 *
 * <p><b>线程模型</b>:所有方法在 {@code sock.eventLoop()} 上调用,无并发。
 *
 * @see <a href="https://github.com/torvalds/linux/blob/master/include/net/tcp.h">Linux tcp_congestion_ops</a>
 * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_rate.c">Linux tcp_rate.c (RateSample 起源)</a>
 */
public interface TcpCongestionControl {

    /**
     * 连接初始化(握手完成 / sock 准备好后调一次)。CC 在这里准备私有状态。
     * 默认实现:no-op。
     */
    default void init(TcpSock sock) {}

    /**
     * 每次 ACK 处理后调用 — 唯一的"算法入口"。
     *
     * <p>CC 根据 {@code rs} 决定要不要修改 {@code sock.cwnd(...)} 与
     * {@link #pacingRateBps(TcpSock)} 输出。修改方式是直接写
     * {@code sock.sender().cwnd(v)} 等 setter,不通过返回值。
     *
     * <p>不同算法读取的字段子集示例:
     * <ul>
     *   <li>NewReno:{@link RateSample#ackedBytes} / {@link RateSample#ackedPackets} /
     *       {@link RateSample#appLimited}</li>
     *   <li>CUBIC:同上 + {@link RateSample#rttUs}(epoch / 曲线)</li>
     *   <li>BBR:{@link RateSample#delivered} / {@link RateSample#priorDelivered} /
     *       {@link RateSample#sendElapsedUs} / {@link RateSample#ackElapsedUs} /
     *       {@link RateSample#minRttUs};忽略 {@code ackedBytes}</li>
     *   <li>Vegas:{@link RateSample#rttUs} + {@link RateSample#minRttUs}(估
     *       queueing delay)</li>
     * </ul>
     *
     * <p><b>注意</b>:Recovery / Loss 状态下 {@code Sender} 会按算法返回的
     * {@link #ssthresh(TcpSock)} 调整 cwnd,然后通过 {@link #onStateChange} 通知
     * CC;CC 在 {@code onAck} 里通常 <b>只在 OPEN / DISORDER</b> 增长 cwnd。
     */
    void onAck(TcpSock sock, RateSample rs);

    /**
     * 进入 Recovery 时返回新的 {@code ssthresh}。
     *
     * <p>NewReno: {@code max(cwnd / 2, 2)}<br>
     * CUBIC: {@code max(cwnd * 0.7, 2)}(beta = 0.7)<br>
     * BBR: 通常 {@code 不修改 ssthresh}(BBR 不依赖丢包),返回当前 cwnd 或一个
     * 哨兵值即可
     */
    int ssthresh(TcpSock sock);

    /**
     * undo 路径返回需要回滚到的 cwnd(伪 Recovery / 伪 RTO 触发时)。
     *
     * <p>NewReno: {@code max(priorCwnd, cwnd)}(不让自然增长被压回)<br>
     * CUBIC: 同上<br>
     * BBR: 由 BBR 内部模型决定
     */
    int undoCwnd(TcpSock sock);

    /**
     * CA 状态机切换通知。{@code Sender} 在状态字段已切换、新 ssthresh 已设置后
     * 调用本方法,允许 CC 在状态边界做反应。默认实现:no-op。
     *
     * <p>典型用途:
     * <ul>
     *   <li>进 Recovery: NewReno 在此把 {@code cwnd = ssthresh + 3}(Fast
     *       Retransmit inflation)</li>
     *   <li>进 Loss: NewReno 在此把 {@code cwnd = 1}</li>
     *   <li>退出 Recovery / Loss: 把 {@code cwnd = ssthresh} 或保持当前</li>
     *   <li>BBR: 重置 ProbeRTT 计时、推进 ProbeBW gain phase 等</li>
     * </ul>
     *
     * <p><b>调用顺序</b>:本方法在 {@code Sender} 已经更新 congestionState 字段
     * <b>之后</b>调用,但通常在 cwnd 调整 <b>之前</b> — CC 在 onStateChange 里
     * 写最终 cwnd。
     */
    default void onStateChange(TcpSock sock,
                                TcpSock.CongestionState oldS,
                                TcpSock.CongestionState newS) {}

    /**
     * 输出 pacing rate(字节/秒)。{@code 0} 表示不限速,{@code Sender} 不在
     * 发送主路径 pace。默认实现:返回 0。
     *
     * <p>BBR 必须实现这个方法;NewReno / CUBIC 默认零成本不 pace。
     */
    default long pacingRateBps(TcpSock sock) {
        return 0L;
    }
}

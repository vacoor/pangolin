package com.github.pangolin.routing.acceptor.tun.net.v2.tcp.core;

/**
 * RTT 采样 SPI — 对齐 Linux {@code tcp_rtt_estimator}(tcp_input.c)路径。
 *
 * <p>仅负责把 RTT 样本注入 {@code srtt / rttvar / rto_backoff} 状态;RTO 读取侧
 * 由 {@link TcpSock#rtoMs()} 直接拿,避免每次 RTO 查询绕一层 SPI。未来若需替换
 * 采样算法(如 CUBIC / BBR 专属 RTT 过滤),实现此接口并在 {@link TcpConnection}
 * 构造期注入即可。
 */
public interface RttEstimator {

    void addSample(TcpConnection conn, long measuredRttUs);

    void backoff(TcpConnection conn);

    void resetBackoff(TcpConnection conn);
}

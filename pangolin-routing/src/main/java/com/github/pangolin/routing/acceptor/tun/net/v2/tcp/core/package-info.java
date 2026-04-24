/**
 * v2 TCP 基础模块(Must-Have)。
 *
 * <p>覆盖 RFC 9293 / RFC 1122 / RFC 6298 / RFC 5681 / RFC 6582 / RFC 3042 /
 * RFC 6928 所要求的必选功能:段格式与 FSM、三次握手与挥手、累计 ACK、滑动窗口、
 * RTO 估计与超时重传、NewReno 拥塞控制、计时器。</p>
 *
 * <p>本包是 v2 TCP 的最小可运行集合。当前架构路线(见 {@code tcp.java.v2.md} §十 /
 * §十五):拥塞控制(NewReno)与丢包恢复(RACK / Fast Retransmit / F-RTO / DSACK undo)
 * 的实现物理内嵌在 {@link com.github.pangolin.routing.acceptor.tun.net.v2.tcp.core.Sender}
 * 和 {@link com.github.pangolin.routing.acceptor.tun.net.v2.tcp.core.TcpAck} 中,
 * 不抽 SPI(R2/R7.3 决策)。</p>
 *
 * <p>可选特性(SACK / Timestamps / WScale / PMTUD / ECN / TFO / MPTCP / CUBIC / RACK-TLP ...)
 * 通过 {@link com.github.pangolin.routing.acceptor.tun.net.v2.tcp.core.TcpFeature}
 * (握手协商 + 选项注入)在 {@code ext} 包下注册接入;当前阶段 {@code TcpFeature} 尚无实现,
 * 所有已支持特性(SACK / Timestamps / WScale)由 {@code core} 直接启用,不走 SPI 路径。</p>
 *
 * <p>RTT 估计({@link com.github.pangolin.routing.acceptor.tun.net.v2.tcp.core.Rfc6298RttEstimator})
 * 不抽 SPI,作为普通类直接使用,对齐 smoltcp。</p>
 */
package com.github.pangolin.routing.acceptor.tun.net.v2.tcp.core;

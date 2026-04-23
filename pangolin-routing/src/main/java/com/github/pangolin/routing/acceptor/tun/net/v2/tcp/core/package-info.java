/**
 * v2 TCP 基础模块(Must-Have)。
 *
 * <p>覆盖 RFC 9293 / RFC 1122 / RFC 6298 / RFC 5681 / RFC 6582 / RFC 3042 /
 * RFC 6928 所要求的必选功能:段格式与 FSM、三次握手与挥手、累计 ACK、滑动窗口、
 * RTO 估计与超时重传、NewReno 拥塞控制、计时器。</p>
 *
 * <p>本包是 v2 TCP 的最小可运行集合,禁止依赖 {@code ext} 下的任何包;可选特性
 * (SACK / Timestamps / WScale / PMTUD / ECN / TFO / MPTCP / CUBIC / RACK-TLP ...)
 * 通过 {@link com.github.pangolin.routing.acceptor.tun.net.v2.tcp.core.TcpFeature}
 * (握手协商 + 选项注入)、
 * {@link com.github.pangolin.routing.acceptor.tun.net.v2.tcp.core.CongestionControl}
 * (拥塞控制算法)、
 * {@link com.github.pangolin.routing.acceptor.tun.net.v2.tcp.core.LossRecovery}
 * (丢包检测与恢复) 三条 SPI 在 {@code ext} 包下注册接入;
 * 同一特性可同时实现多条 SPI(如 SACK 同时是 {@code TcpFeature} 与 {@code LossRecovery})。</p>
 *
 * <p>RTT 估计({@link com.github.pangolin.routing.acceptor.tun.net.v2.tcp.core.Rfc6298RttEstimator})
 * 不抽 SPI,作为普通类直接使用,对齐 smoltcp。</p>
 */
package com.github.pangolin.routing.acceptor.tun.net.v2.tcp.core;

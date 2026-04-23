/**
 * 可选特性:RACK-TLP(RFC 8985)。
 *
 * <p>占位包 — 用于容纳 RACK 时间窗丢失检测与 TLP 探测包的后续 {@code LossRecovery}
 * 实现。当前 RACK 逻辑仍内嵌在 {@code core.TcpSock} / {@code core.TcpAck} /
 * {@code core.TcpRetransmitter} 中,本包在 Phase E(v2 TCP 包重构 · 提交 3)为
 * <b>纯文档锚点</b>:让 {@code tcp.java.md} / {@code tcp-gap-phase4.md} 中对 RACK 的
 * 引用有稳定的物理坐标,便于后续迁移。
 *
 * <p>本包仅依赖 {@code v2.tcp.core};默认开启,由 {@code TcpConfig} 的 SACK /
 * Timestamps 开关间接决定(RACK 依赖对端 TS/SACK 能力)。通过
 * {@link com.github.pangolin.routing.acceptor.tun.net.v2.tcp.core.LossRecovery}
 * SPI 注入基础收发路径,不修改 {@code core} 代码。
 */
package com.github.pangolin.routing.acceptor.tun.net.v2.tcp.ext.recovery.rack;

/**
 * 可选特性:RACK-TLP(RFC 8985)占位包。
 *
 * <p>当前 RACK / TLP 逻辑已在 R2 / R7.x 系列重构中物理内嵌在
 * {@code core.Sender} / {@code core.TcpAck} / {@code core.TcpOutput}
 * 中(不走 SPI,见 {@code tcp.java.v2.md} §十决策)。
 *
 * <p>本包当前仅作文档锚点;如未来需要把 RACK/TLP 拆成可插拔扩展,再在此处落地。
 */
package com.github.pangolin.routing.acceptor.tun.net.v2.tcp.ext.recovery.rack;

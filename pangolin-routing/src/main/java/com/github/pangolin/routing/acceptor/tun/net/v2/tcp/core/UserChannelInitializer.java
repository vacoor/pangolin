package com.github.pangolin.routing.acceptor.tun.net.v2.tcp.core;

/**
 * v2 TCP 协议栈在连接进入 ESTABLISHED 时回调的用户层初始化钩子。
 *
 * <p>由 {@link TcpMultiplexer#tcp_init_transfer(TcpSock)} 在 {@code tcp_try_establish}
 * 推进状态机之后、数据路径打开之前调用;实现方应完成:
 * <ol>
 *   <li>创建用户 Channel(通常是 netty 子包的 {@code TcpChannel});</li>
 *   <li>通过 {@link TcpSock#userChannelBridge(UserChannelBridge)} 安装回调桥;</li>
 *   <li>把 channel {@code register} 到 {@code sock.eventLoop()} 并 {@code fireChannelActive}。</li>
 * </ol>
 *
 * <p><b>线程模型</b>:本方法在 {@code sock.eventLoop()} 内同步调用,实现方无需再跨线程跳转。
 *
 * <p><b>与 backend childChannel 的关系</b>:{@link TcpMultiplexer} 按 "userChannel 优先"
 * 策略分流 — 安装 bridge 后 backend 透传路径自动让位。若 Tcp4Multiplexer 同时配置了
 * {@code SocketChannelFactory} 与本 initializer,行为等价于模式 C(用户 pipeline 处理 + 可选
 * 后端转发),需由用户 handler 自行协调。
 */
@FunctionalInterface
public interface UserChannelInitializer {
    void onEstablished(TcpSock sock, TcpMultiplexer multiplexer);
}

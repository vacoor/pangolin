package com.github.pangolin.routing.acceptor.tun.net.v2.tcp.core;

import io.netty.buffer.ByteBuf;

/**
 * 核心 TCP 栈与外部 Netty {@code TcpChannel} 之间的最小回调桥。
 *
 * <p>{@code core} 包不能直接依赖 {@code netty} 子包(会形成 core → netty → core
 * 循环),本接口抽出 TCP 协议栈仅需调用的四个动作:入站数据交付、对端
 * FIN / RST 通知、writability 回调。{@code TcpChannel} 在构造时把实例注入
 * {@link TcpSock#userChannelBridge(UserChannelBridge)},由
 * {@link TcpMultiplexer} / {@link TcpAck} 等在 EL 线程上驱动。
 *
 * <p>所有方法<b>必须</b>在 {@code sock.eventLoop()} 内调用;bridge 实现不负责
 * 跨线程跳转。
 */
public interface UserChannelBridge {

    /**
     * 有新 payload 可交付应用层。被调用方负责 release 入参 buf(契约与
     * {@link TcpMultiplexer.DataConsumer#onData} 一致)。
     *
     * <p>对应 {@link TcpMultiplexer#consume} 的 userChannel 分支。
     */
    void onInboundData(ByteBuf data);

    /**
     * 对端 FIN 已到达、{@code tcp_fin} 已推进状态机,但本端仍可写(CLOSE_WAIT
     * 等状态)。对应 Netty {@code ChannelInputShutdownEvent} 语义。
     */
    void onPeerFin();

    /**
     * 收到 RST 或本端主动发 RST;sock 即将 {@code tcp_done} 销毁。
     * 实现应 {@code fireExceptionCaught} 后 {@code closeForcibly}。
     */
    void onReset(Throwable cause);

    /**
     * ACK 推进后或发送窗口松动,提示 {@code TcpChannel} 重新评估 writability 水位。
     * 对应 Netty {@code ChannelOutboundBuffer#setUserDefinedWritability}。
     */
    void onWritabilityChanged();

    /**
     * {@link TcpMultiplexer#inet_csk_destroy_sock(TcpSock)} 将要销毁 sock,
     * bridge 在此完成最终 {@code closeForcibly} / pipeline.fireChannelInactive,
     * 保证 channel 事件顺序与 {@code NioSocketChannel} 一致。
     */
    void onSocketDestroyed();
}

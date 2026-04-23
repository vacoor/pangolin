package com.github.pangolin.routing.acceptor.tun.net.v2.tcp.core.hook;

import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.core.SegmentDispatcher;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.core.TcpSock;
import io.netty.buffer.ByteBuf;

/**
 * 挂在 {@link TcpSock} 上的长期事件回调处理器 — 对齐 Linux 内核 {@code sk->sk_user_data}
 * 的角色,连接建立后核心 TCP 栈把入站数据、对端 FIN/RST、writability 变化等事件回调到这里。
 *
 * <p>{@code core} 包不能直接依赖 {@code netty} 子包(会形成 core → netty → core 循环),
 * 本接口抽出 TCP 协议栈仅需调用的五个动作。实现类(如 netty 子包的 {@code TcpChannel}、
 * ext.backend 子包的 {@code TcpPassthroughHandler})在 {@link TcpSockInitializer#onEstablished}
 * 期间通过 {@link TcpSock#handler(TcpSockHandler)} 挂入。
 *
 * <p>所有方法<b>必须</b>在 {@code sock.eventLoop()} 内调用;handler 实现不负责跨线程跳转。
 */
public interface TcpSockHandler {

    /**
     * 有新 payload 可交付应用层。被调用方负责 release 入参 buf。
     *
     * <p>对应 {@link SegmentDispatcher#consume} 的入站数据派发分支。
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
     * ACK 推进后或发送窗口松动,提示 handler 重新评估 writability 水位。
     * 对应 Netty {@code ChannelOutboundBuffer#setUserDefinedWritability}。
     */
    void onWritabilityChanged();

    /**
     * {@link SegmentDispatcher#inet_csk_destroy_sock(TcpSock)} 将要销毁 sock,
     * handler 在此完成最终 {@code closeForcibly} / pipeline.fireChannelInactive,
     * 保证 channel 事件顺序与 {@code NioSocketChannel} 一致。
     */
    void onSocketDestroyed();
}

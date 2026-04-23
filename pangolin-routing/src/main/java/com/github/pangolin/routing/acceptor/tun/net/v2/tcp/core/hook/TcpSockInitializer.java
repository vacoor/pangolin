package com.github.pangolin.routing.acceptor.tun.net.v2.tcp.core.hook;

import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.core.TcpMultiplexer;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.core.TcpRequestSock;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.core.TcpSock;
import io.netty.channel.EventLoop;

/**
 * 挂在 {@link TcpSock} 上的一次性建连装配钩子 — v2 TCP 协议栈在连接的不同阶段回调本接口,
 * 由实现方负责创建并挂 {@link TcpSockHandler},以及(可选)参与 SYN 阶段的协议处理。
 *
 * <p><b>钩子顺序</b>:
 * <ol>
 *   <li>{@link #onRequest} — {@code tcp_v4_conn_request} 收到 SYN、半连接入队后调用,
 *       实现方在此决定是否 / 何时 {@link TcpMultiplexer#sendSynAck} 响应。默认实现:立发。</li>
 *   <li>{@link #proposeEventLoop} — {@code tcp_v4_syn_recv_sock} 为 child sock 绑 EL 时调用,
 *       返回非空时优先采用(如 backend channel 的 EL)。默认返回 null → 回退 {@code tcpGroup.next()}。</li>
 *   <li>{@link #onEstablished} — {@code initTransfer} 子 sock 进入 ESTABLISHED 时调用,
 *       实现方 <b>必须</b> {@link TcpSock#handler} 安装回调处理器。</li>
 *   <li>{@link #onRequestDestroyed} — {@link TcpMultiplexer#inet_csk_destroy_sock(TcpRequestSock)}
 *       销毁半连接前调用,实现方在此释放挂在 {@link TcpRequestSock#attachment()} 的资源。</li>
 * </ol>
 *
 * <p><b>线程模型</b>:所有方法均在相应 sock / req 的 EL 上同步调用,实现方无需跨线程跳转。
 */
public interface TcpSockInitializer {

    /**
     * 子 sock 进入 ESTABLISHED 时触发 — 实现方 <b>必须</b> 在此创建并挂
     * {@link TcpSockHandler},否则 {@link TcpMultiplexer#consume} 会走"无 handler"
     * 防御分支 release inbound buf。
     */
    void onEstablished(TcpSock sock, TcpMultiplexer multiplexer);

    /**
     * SYN 到达、半连接入队后触发 — 默认立发 SYN-ACK。
     * {@code TcpPassthroughInitializer}(ext.backend 子包)覆盖此方法,先发起 backend connect,
     * 连上后再 sendSynAck;{@link #DENY} 覆盖此方法,立发 RST 并销毁 req,对齐 Linux
     * "无 listener" 语义。
     *
     * <p><b>契约</b>:
     * <ul>
     *   <li>进入时 req 已完成 {@code openreqInit} / {@code addToHalfQueue},
     *       {@link TcpRequestSock#net()} 指向入站 {@code ChannelHandlerContext},
     *       {@link TcpRequestSock#synPacket()} 指向 <b>已 retain 一次</b> 的 SYN pkt。
     *       lifetime 与 req 绑定,由 {@code moveToEstablished} 或
     *       {@link TcpMultiplexer#inet_csk_destroy_sock(TcpRequestSock)} 负责释放,
     *       实现方不参与 retain/release。</li>
     *   <li>实现方要把 SYN-ACK 发出去就调 {@code mux.sendSynAck(req)} —— 该方法内部装
     *       {@code synAckFailureAction} / {@code handshakeCloseListener} 并触发 SYN-ACK 发送。</li>
     *   <li>直接 RST 的路径(DENY)调 {@code mux.send_reset(req.net(), req.synPacket(), err)} +
     *       {@code mux.inet_csk_destroy_sock(req)},后者统一释放 synPacket。</li>
     * </ul>
     */
    default void onRequest(TcpRequestSock req, TcpMultiplexer multiplexer) {
        multiplexer.sendSynAck(req);
    }

    /**
     * 为 child sock 推荐专属 {@link EventLoop} — 默认返回 null,让
     * {@code Tcp4Multiplexer#tcp_v4_syn_recv_sock} 回退 {@code tcpGroup.next()}。
     * {@code TcpPassthroughInitializer}(ext.backend 子包)覆盖为 backend channel 的 EL,
     * 保留 v1 "状态机与 backend I/O 同线程" 语义。
     */
    default EventLoop proposeEventLoop(TcpRequestSock req, TcpMultiplexer multiplexer) {
        return null;
    }

    /**
     * 半连接 req 被 {@link TcpMultiplexer#inet_csk_destroy_sock(TcpRequestSock)} 销毁前触发 —
     * 默认 no-op。实现方若在 {@link TcpRequestSock#attachment()} 挂了资源(如 backend state),
     * 在此释放。
     */
    default void onRequestDestroyed(TcpRequestSock req) {
    }

    /**
     * 显式关闭端口 — 对齐 Linux "无 listener → RST" 语义。SYN 阶段立发 RST + 销毁 req,
     * 不做 3WHS。
     *
     * <p>若配置中主动想拒绝监听,显式传本常量;漏配请求会在 {@code TcpMultiplexer} 构造期
     * 命中 {@code Objects.requireNonNull(initializer)} 立即 NPE,避免隐式静默丢弃。
     */
    TcpSockInitializer DENY = new TcpSockInitializer() {
        @Override
        public void onRequest(TcpRequestSock req, TcpMultiplexer multiplexer) {
            multiplexer.send_reset(req.net(), req.synPacket(), -88);
            multiplexer.inet_csk_destroy_sock(req);
        }

        @Override
        public void onEstablished(TcpSock sock, TcpMultiplexer multiplexer) {
            // unreachable:onRequest 已销毁 req,永不进入 ESTABLISHED
        }
    };
}

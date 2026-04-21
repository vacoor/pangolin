package com.github.pangolin.routing.acceptor.tun.net.v2.tcp.netty;

import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.core.TcpMultiplexer;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.core.TcpSock;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.core.UserChannelInitializer;
import io.netty.channel.ChannelFuture;

/**
 * 用户 Channel 工厂 — 在 v2 三次握手完成后 (tcp_init_transfer 阶段) 由 {@link TcpMultiplexer}
 * 调用,生产一个已挂 pipeline 的 {@link TcpChannel}。
 *
 * <p>典型用法:
 * <pre>{@code
 * TcpChannelFactory factory = (sock, mux) -> {
 *     TcpChannel ch = new TcpChannel(sock, mux);
 *     ch.pipeline().addLast(new HttpServerCodec(), new MyBizHandler());
 *     return ch;
 * };
 * new Tcp4Multiplexer(config, factory);
 * }</pre>
 *
 * <p>工厂必须返回**尚未 register** 的 channel;register 与 {@code fireChannelActive} 由
 * {@link #onEstablished} 的默认实现自动触发,保证事件顺序与 {@code NioSocketChannel} 一致。
 *
 * <p>本接口直接实现 {@link UserChannelInitializer},可以原样传入 {@code Tcp4Multiplexer}
 * 的工厂模式构造,无需手写 adapter。
 */
@FunctionalInterface
public interface TcpChannelFactory extends UserChannelInitializer {

    TcpChannel create(TcpSock sock, TcpMultiplexer multiplexer);

    @Override
    default void onEstablished(TcpSock sock, TcpMultiplexer multiplexer) {
        final TcpChannel ch = create(sock, multiplexer);
        /*
         * register 必须在 sock.eventLoop() 上执行;本方法由 tcp_init_transfer 在 EL
         * 内同步调用,直接 register 即可。Netty 会在 register 完成后自动
         * fireChannelActive(若 isActive() 已为 true —— 我们在 ESTABLISHED 后调用,
         * 满足条件)。
         */
        ChannelFuture reg = sock.eventLoop().register(ch);
        if (reg.cause() != null) {
            // register 失败:立即 forcibly close 释放工厂已挂 pipeline 中的资源
            ch.unsafe().closeForcibly();
        }
    }
}

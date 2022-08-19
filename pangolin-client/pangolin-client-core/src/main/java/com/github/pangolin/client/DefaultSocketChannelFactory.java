package com.github.pangolin.client;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.util.concurrent.DefaultThreadFactory;

import java.util.concurrent.ThreadFactory;

public class DefaultSocketChannelFactory implements SocketChannelFactory {
    private final ThreadFactory threadFactory;

    public DefaultSocketChannelFactory() {
        threadFactory = new DefaultThreadFactory("SocketChannel-Pool", true);
    }

    @Override
    public SocketChannel newSocketChannel(final String host, final int port,
                                          final int connectTimeoutMs, final ChannelHandler handler) throws InterruptedException {
        final EventLoopGroup group = new NioEventLoopGroup(1, threadFactory);
        SocketChannel socketChannel = null;
        try {
            final Bootstrap b = new Bootstrap();
            b.option(ChannelOption.TCP_NODELAY, true);
            b.option(ChannelOption.SO_KEEPALIVE, true);
            b.option(ChannelOption.CONNECT_TIMEOUT_MILLIS, connectTimeoutMs);
            b.group(group).channel(NioSocketChannel.class).handler(handler);
            socketChannel = (SocketChannel) b.connect(host, port).sync().channel();
        } finally {
            if (null != socketChannel) {
                socketChannel.closeFuture().addListener(new ChannelFutureListener() {
                    @Override
                    public void operationComplete(final ChannelFuture future) {
                        group.shutdownGracefully();
                    }
                });
            } else {
                group.shutdownGracefully();
            }
        }
        return socketChannel;
    }

}
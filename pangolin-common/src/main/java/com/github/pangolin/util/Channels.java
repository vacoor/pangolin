package com.github.pangolin.util;

import io.netty.bootstrap.Bootstrap;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;

public class Channels {

    public static ChannelFuture listen(final String listenHost, final int listenPort,
                                       final NioEventLoopGroup bossGroup, final NioEventLoopGroup workerGroup,
                                       final ChannelHandler initializer) throws InterruptedException {
        final ServerBootstrap serverBootstrap = new ServerBootstrap();
        serverBootstrap.option(ChannelOption.SO_REUSEADDR, true);
        serverBootstrap.childOption(ChannelOption.TCP_NODELAY, true);
        serverBootstrap.childOption(ChannelOption.SO_KEEPALIVE, true);
        serverBootstrap.group(bossGroup, workerGroup).channel(NioServerSocketChannel.class);
        serverBootstrap.childHandler(initializer);

        if (null == listenHost) {
            return serverBootstrap.bind(listenPort);
        } else {
            return serverBootstrap.bind(listenHost, listenPort);
        }
    }

    public static ChannelFuture open(final String hostname, final int port,
                                     final EventLoopGroup group, final ChannelHandler initializer) throws InterruptedException {
        return open(hostname, port, true, group, initializer);
    }

    public static ChannelFuture open(final String hostname, final int port,
                                     final boolean autoRead, final EventLoopGroup group,
                                     final ChannelHandler initializer) throws InterruptedException {
        final Bootstrap b = new Bootstrap();
        b.option(ChannelOption.AUTO_READ, autoRead);
        b.option(ChannelOption.TCP_NODELAY, true);
        b.option(ChannelOption.SO_KEEPALIVE, true);
        b.option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 5000);
        b.group(group).channel(NioSocketChannel.class).handler(initializer);
        // return b.connect(hostname, port).sync().channel();
        return b.connect(hostname, port);
    }

}
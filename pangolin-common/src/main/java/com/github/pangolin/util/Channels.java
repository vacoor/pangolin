package com.github.pangolin.util;

import io.netty.bootstrap.Bootstrap;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.resolver.AddressResolverGroup;

import java.net.InetSocketAddress;
import java.net.SocketAddress;

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

    public static ChannelFuture open(final String hostname, final int port, final boolean autoRead,
                                     final EventLoopGroup group, final ChannelHandler initializer) throws InterruptedException {
        return open(hostname, port, null, autoRead, group, initializer);
    }

    public static ChannelFuture open(final String hostname, final int port,
                                     final AddressResolverGroup<SocketAddress> resolver, final boolean autoRead,
                                     final EventLoopGroup group, final ChannelHandler initializer) throws InterruptedException {
        return open(new InetSocketAddress(hostname, port), resolver, autoRead, group, initializer);
    }

    public static ChannelFuture open(final SocketAddress remoteAddress, final boolean autoRead,
                                     final EventLoopGroup group, final ChannelHandler initializer) throws InterruptedException {
        return open(remoteAddress, null, autoRead, group, initializer);
    }

    public static ChannelFuture open(final SocketAddress remoteAddress,
                                     final AddressResolverGroup<SocketAddress> resolver, final boolean autoRead,
                                     final EventLoopGroup group, final ChannelHandler initializer) throws InterruptedException {
        final Bootstrap b = new Bootstrap();
        b.option(ChannelOption.AUTO_READ, autoRead);
        b.option(ChannelOption.TCP_NODELAY, true);
        b.option(ChannelOption.SO_KEEPALIVE, true);
        b.option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 10000);
        b.resolver(resolver).group(group).channel(NioSocketChannel.class).handler(initializer);
        return b.connect(remoteAddress);
    }

        public static void closeOnFlush ( final Channel channel){
            if (null != channel && channel.isActive()) {
                channel.writeAndFlush(Unpooled.EMPTY_BUFFER).addListener(ChannelFutureListener.CLOSE);
            }
        }

        public static void closeWebSocketOnClose ( final Channel channel, final ChannelHandlerContext webSocketContext){
            channel.closeFuture().addListener(new ChannelFutureListener() {
                @Override
                public void operationComplete(final ChannelFuture future) throws Exception {
                    if (webSocketContext.channel().isActive()) {
                        WebSocketUtils.normalClose(webSocketContext, "Disconnect");
                    }
                }
            });
        }

        public static void shutdownGroupOnClose ( final Channel channel, final EventLoopGroup eventLoopGroup){
            channel.closeFuture().addListener(new ChannelFutureListener() {
                @Override
                public void operationComplete(final ChannelFuture future) {
                    eventLoopGroup.shutdownGracefully();
                }
            });
        }

    }
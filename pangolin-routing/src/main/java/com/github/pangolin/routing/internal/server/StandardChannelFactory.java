package com.github.pangolin.routing.internal.server;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.resolver.AddressResolverGroup;

import java.net.SocketAddress;

public class StandardChannelFactory implements ChannelFactory {

    public ChannelFuture open(final SocketAddress remoteAddress, final boolean autoRead,
                              final long connTimeoutMs, final EventLoopGroup group, final ChannelHandler handler) {
        final Bootstrap b = new Bootstrap();
        b.option(ChannelOption.AUTO_READ, autoRead);
        b.option(ChannelOption.TCP_NODELAY, true);
        b.option(ChannelOption.SO_KEEPALIVE, true);
        b.option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 10000);
        b.option(ChannelOption.SO_RCVBUF, 32 * 1024);// 读缓冲区为32k
        b.group(group).channel(NioSocketChannel.class).handler(handler);
        return b.connect(remoteAddress);
    }
}
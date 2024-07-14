package com.github.pangolin.routing.handler.internal.server.support;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.socket.nio.NioDatagramChannel;

public class StandardDatagramChannelFactory implements DatagramChannelFactory {

    @Override
    public ChannelFuture open(final int connTimeoutMs, final EventLoopGroup group, final ChannelHandler handler) {
        final Bootstrap b = new Bootstrap()
                .group(group)
                .channel(NioDatagramChannel.class)
                .option(ChannelOption.SO_BROADCAST, false)
                .handler(handler);
        return b.bind(0);
    }

}
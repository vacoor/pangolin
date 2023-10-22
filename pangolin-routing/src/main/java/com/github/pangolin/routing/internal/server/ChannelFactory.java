package com.github.pangolin.routing.internal.server;

import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;

import java.net.SocketAddress;

public interface ChannelFactory {

    ChannelFuture open(final SocketAddress remoteAddress, final boolean autoRead,
                       final long connTimeoutMs, final EventLoopGroup group, final ChannelHandler handler);

    }

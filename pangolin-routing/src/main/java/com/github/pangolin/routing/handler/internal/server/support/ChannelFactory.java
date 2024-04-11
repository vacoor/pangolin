package com.github.pangolin.routing.handler.internal.server.support;

import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandler;
import io.netty.channel.EventLoopGroup;

import java.net.SocketAddress;

public interface ChannelFactory {

  ChannelFuture open(final SocketAddress remoteAddress,
                     final int connTimeoutMs, final boolean autoRead, final EventLoopGroup group, final ChannelHandler handler);

}

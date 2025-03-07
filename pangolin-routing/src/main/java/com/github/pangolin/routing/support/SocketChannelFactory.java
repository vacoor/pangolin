package com.github.pangolin.routing.support;

import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandler;
import io.netty.channel.EventLoopGroup;

import java.net.SocketAddress;

public interface SocketChannelFactory {

    /**
     * Open socket channel.
     *
     * @param remoteAddress remote address
     * @param connTimeoutMs connect timeout millis
     * @param autoRead      auto read
     * @param group         event loop
     * @param handler       channel handler
     * @return channel future
     */
    ChannelFuture open(final SocketAddress remoteAddress, final int connTimeoutMs,
                       final boolean autoRead, final EventLoopGroup group, final ChannelHandler handler);

}

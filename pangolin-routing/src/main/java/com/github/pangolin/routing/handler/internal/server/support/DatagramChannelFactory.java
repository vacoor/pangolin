package com.github.pangolin.routing.handler.internal.server.support;

import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandler;
import io.netty.channel.EventLoopGroup;

public interface DatagramChannelFactory {

    /**
     * Open datagram channel.
     *
     * @param connTimeoutMs connect timeout millis
     * @param group         event loop
     * @param handler       channel handler
     * @return channel future
     */
    ChannelFuture open(final int connTimeoutMs, final EventLoopGroup group, final ChannelHandler handler);

}
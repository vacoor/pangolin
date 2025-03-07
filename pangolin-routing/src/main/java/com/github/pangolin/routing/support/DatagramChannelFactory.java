package com.github.pangolin.routing.support;

import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandler;
import io.netty.channel.EventLoopGroup;

import java.net.InetSocketAddress;

public interface DatagramChannelFactory {

    /**
     * Open datagram channel.
     *
     * @param connTimeoutMs connect timeout millis
     * @param group         event loop
     * @param handler       channel handler
     * @return channel future
     */
    ChannelFuture open(final InetSocketAddress destination, final int connTimeoutMs, final EventLoopGroup group, final ChannelHandler handler);

}
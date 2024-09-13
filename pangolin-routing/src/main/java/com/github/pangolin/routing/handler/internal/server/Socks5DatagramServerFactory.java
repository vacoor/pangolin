package com.github.pangolin.routing.handler.internal.server;

import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;

import java.net.InetSocketAddress;

@Deprecated
public interface Socks5DatagramServerFactory {

    @Deprecated
    ChannelFuture createServer(final Channel parent, final InetSocketAddress sender);

}
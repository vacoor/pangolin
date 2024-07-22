package com.github.pangolin.routing.handler.internal.server;

import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;

import java.net.InetSocketAddress;

public interface Socks5DatagramServerFactory {

    ChannelFuture createServer(final Channel parent, final InetSocketAddress sender);

}
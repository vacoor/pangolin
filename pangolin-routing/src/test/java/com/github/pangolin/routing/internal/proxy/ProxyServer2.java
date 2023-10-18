package com.github.pangolin.routing.internal.proxy;

import io.netty.channel.ChannelHandler;

import java.net.InetSocketAddress;

public interface ProxyServer2 {

    String getName();

    ChannelHandler newProxyHandler(final InetSocketAddress sa);

}
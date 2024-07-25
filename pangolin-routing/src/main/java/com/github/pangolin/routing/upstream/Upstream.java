package com.github.pangolin.routing.upstream;

import io.netty.channel.ChannelHandler;

import java.net.InetSocketAddress;

public interface Upstream {

    String getName();

    ChannelHandler newSocketProxyHandler(final InetSocketAddress destination);

    ChannelHandler newDatagramProxyHandler(final InetSocketAddress destination);

}

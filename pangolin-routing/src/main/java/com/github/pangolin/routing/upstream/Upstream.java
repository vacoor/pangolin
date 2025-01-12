package com.github.pangolin.routing.upstream;

import io.netty.channel.ChannelHandler;

import java.net.InetSocketAddress;
import java.net.SocketAddress;

public interface Upstream {

    String name();

    SocketAddress address();

    boolean isVirtual();

    ChannelHandler newSocketProxyHandler(final InetSocketAddress destination);

    ChannelHandler newDatagramProxyHandler(final InetSocketAddress destination);

}

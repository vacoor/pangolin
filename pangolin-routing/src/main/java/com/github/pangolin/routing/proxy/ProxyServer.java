package com.github.pangolin.routing.proxy;

import io.netty.channel.ChannelHandler;

import java.net.InetSocketAddress;

public interface ProxyServer {

    String getName();

    ChannelHandler newSocketProxyHandler(InetSocketAddress sa);

    ChannelHandler newDatagramProxyHandler(InetSocketAddress sa);

}

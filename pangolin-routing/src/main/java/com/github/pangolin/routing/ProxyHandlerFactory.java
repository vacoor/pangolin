package com.github.pangolin.routing;

import io.netty.channel.ChannelHandler;

import java.net.InetSocketAddress;

public interface ProxyHandlerFactory {

    ChannelHandler newProxyHandler(InetSocketAddress sa);

}
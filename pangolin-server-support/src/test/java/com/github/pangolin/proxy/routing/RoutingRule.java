package com.github.pangolin.proxy.routing;

import io.netty.channel.ChannelHandler;

import java.net.SocketAddress;

public interface RoutingRule {

    boolean matches(final SocketAddress sourceAddress, final SocketAddress destinationAddress);

    ChannelHandler newProxyHandler();

}
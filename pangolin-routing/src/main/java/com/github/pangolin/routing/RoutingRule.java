package com.github.pangolin.routing;

import io.netty.channel.ChannelHandler;

import java.net.InetSocketAddress;

public interface RoutingRule {

    boolean matches(final InetSocketAddress destinationAddress);

    ChannelHandler newProxyHandler();

}
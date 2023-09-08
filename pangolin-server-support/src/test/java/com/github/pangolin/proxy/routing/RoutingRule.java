package com.github.pangolin.proxy.routing;

import io.netty.channel.ChannelHandler;

import java.net.InetSocketAddress;
import java.net.SocketAddress;

public interface RoutingRule {

    boolean matches(final InetSocketAddress destinationAddress);

    ChannelHandler newProxyHandler();

}
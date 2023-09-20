package com.github.pangolin.routing.internal.server;

import io.netty.channel.ChannelHandler;

/**
 *
 */
public interface ProxyServer {

    String name();

    ChannelHandler newProxyHandler();

}

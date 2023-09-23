package com.github.pangolin.routing.pattern;

import io.netty.channel.ChannelHandler;

public interface ProxyHandlerFactory {

    String name();

    ChannelHandler newProxyHandler();

}
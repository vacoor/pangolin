package com.github.pangolin.routing.pattern;

import io.netty.channel.ChannelHandler;

public interface ProxyHandlerFactory {

    ChannelHandler newProxyHandler();

}
package com.github.pangolin.routing;

import io.netty.channel.ChannelHandler;

public interface ProxyHandlerFactory {

    ChannelHandler newProxyHandler();

}
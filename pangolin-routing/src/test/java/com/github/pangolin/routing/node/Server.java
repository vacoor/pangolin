package com.github.pangolin.routing.node;

import com.github.pangolin.routing.pattern.ProxyHandlerFactory;
import io.netty.channel.ChannelHandler;

public interface Server extends ProxyHandlerFactory {

    String getName();

    ChannelHandler newProxyHandler();

}

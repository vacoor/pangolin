package com.github.pangolin.routing.node.spi;

import com.github.pangolin.routing.pattern.ProxyHandlerFactory;
import io.netty.channel.ChannelHandler;

/**
 *
 */
public interface ProxyInstance extends ProxyHandlerFactory {

    String getName();

    ChannelHandler newProxyHandler();

}

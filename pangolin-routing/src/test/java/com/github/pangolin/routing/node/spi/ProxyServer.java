package com.github.pangolin.routing.node.spi;

import com.github.pangolin.routing.pattern.ProxyHandlerFactory;
import io.netty.channel.ChannelHandler;

/**
 *
 */
public interface ProxyServer extends ProxyHandlerFactory {

    String name();

    ChannelHandler newProxyHandler();

}

package com.github.pangolin.proxy.routing;

import io.netty.channel.ChannelHandler;

import java.net.URI;

public interface ProxyFactory {

    ChannelHandler newProxyHandler(final URI proxyServerUri);

}

package com.github.pangolin.proxy.routing;

import io.netty.channel.ChannelHandler;

import java.net.SocketAddress;

public interface ProxySelector {

    ChannelHandler newProxyHandler(final SocketAddress destination);

}

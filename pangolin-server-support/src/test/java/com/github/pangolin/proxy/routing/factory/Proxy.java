package com.github.pangolin.proxy.routing.factory;

import io.netty.channel.ChannelHandler;

public interface Proxy {

    ChannelHandler newProxyHandler();

}

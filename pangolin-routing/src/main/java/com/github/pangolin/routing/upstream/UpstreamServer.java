package com.github.pangolin.routing.upstream;

import io.netty.channel.ChannelHandler;

import java.net.InetSocketAddress;

public interface UpstreamServer {

    String getName();

    ChannelHandler newSocketProxyHandler(InetSocketAddress sa);

    ChannelHandler newDatagramProxyHandler(InetSocketAddress sa);

}

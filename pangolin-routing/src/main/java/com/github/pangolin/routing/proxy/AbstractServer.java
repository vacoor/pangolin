package com.github.pangolin.routing.proxy;

import io.netty.channel.ChannelHandler;
import lombok.Getter;

import java.net.InetSocketAddress;

public abstract class AbstractServer implements ProxyServer {
    @Getter
    protected final String name;

    protected AbstractServer(final String name) {
        this.name = name;
    }

    public ChannelHandler newProxyHandler(final InetSocketAddress destination) {
        return newSocketProxyHandler(destination);
    }

    public abstract ChannelHandler newSocketProxyHandler(final InetSocketAddress destination);

    public ChannelHandler newDatagramProxyHandler(final InetSocketAddress destination) {
        return null;
    }

}
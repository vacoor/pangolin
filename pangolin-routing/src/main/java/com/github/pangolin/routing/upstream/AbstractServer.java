package com.github.pangolin.routing.upstream;

import io.netty.channel.ChannelHandler;
import lombok.Getter;

import java.net.InetSocketAddress;

public abstract class AbstractServer implements UpstreamServer {
    @Getter
    protected final String name;

    protected AbstractServer(final String name) {
        this.name = name;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public abstract ChannelHandler newSocketProxyHandler(final InetSocketAddress destination);

    /**
     * {@inheritDoc}
     */
    @Override
    public ChannelHandler newDatagramProxyHandler(final InetSocketAddress destination) {
        return null;
    }

}
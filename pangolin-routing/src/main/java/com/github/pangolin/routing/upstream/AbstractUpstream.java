package com.github.pangolin.routing.upstream;

import io.netty.channel.ChannelHandler;
import lombok.Getter;

import java.net.InetSocketAddress;

public abstract class AbstractUpstream implements Upstream {
    @Getter
    protected final String name;

    protected AbstractUpstream(final String name) {
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
    public abstract ChannelHandler newDatagramProxyHandler(final InetSocketAddress destination);

    @Override
    public String toString() {
        return name;
    }
}
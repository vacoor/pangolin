package com.github.pangolin.routing.upstream;

import io.netty.channel.ChannelHandler;

import java.net.InetSocketAddress;

public abstract class AbstractUpstream implements Upstream {
    protected final String name;

    protected AbstractUpstream(final String name) {
        this.name = name;
    }

    public String name() {
        return name;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public abstract ChannelHandler newSocketProxyHandler(final InetSocketAddress destination);

    public ChannelHandler[] newSocketProxyHandlers(final InetSocketAddress destination) {
        final ChannelHandler h = newSocketProxyHandler(destination);
        return null != h ? new ChannelHandler[]{h} : new ChannelHandler[0];
    }

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
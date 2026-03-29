package com.github.pangolin.routing.upstream;

import io.netty.channel.ChannelHandler;

import java.net.InetSocketAddress;
import java.net.SocketAddress;

/**
 *
 */
public abstract class DynamicUpstream extends AbstractUpstream {

    public DynamicUpstream(final String name) {
        super(name);
    }

    @Override
    public SocketAddress address() {
        return null;
    }

    @Override
    public boolean isVirtual() {
        return true;
    }

    @Override
    public ChannelHandler newSocketProxyHandler(final InetSocketAddress destination) {
        final Upstream upstream = choose(destination);
        return null != upstream ? upstream.newSocketProxyHandler(destination) : null;
    }

    @Override
    public ChannelHandler[] newSocketProxyHandlers(final InetSocketAddress destination) {
        final Upstream upstream = choose(destination);
        return null != upstream ? upstream.newSocketProxyHandlers(destination) : new ChannelHandler[0];
    }

    @Override
    public ChannelHandler newDatagramProxyHandler(final InetSocketAddress destination) {
        final Upstream upstream = choose(destination);
        return null != upstream ? upstream.newDatagramProxyHandler(destination) : null;
    }

    protected abstract Upstream choose(final InetSocketAddress destination);

}

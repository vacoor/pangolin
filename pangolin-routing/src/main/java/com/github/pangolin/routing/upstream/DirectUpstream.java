package com.github.pangolin.routing.upstream;

import io.netty.channel.ChannelHandler;

import java.net.InetSocketAddress;
import java.net.SocketAddress;

public class DirectUpstream extends AbstractUpstream {
    public static final String NAME = "DIRECT";
    public static final DirectUpstream INSTANCE = new DirectUpstream();

    private DirectUpstream() {
        super(NAME);
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
    public boolean isAvailable() {
        return true;
    }

    @Override
    public ChannelHandler newSocketProxyHandler(final InetSocketAddress destination) {
        return null;
    }

    @Override
    public ChannelHandler newDatagramProxyHandler(final InetSocketAddress destination) {
        return null;
    }

}

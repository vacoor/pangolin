package com.github.pangolin.routing.beta.fakedns;

import com.github.pangolin.routing.handler.internal.server.support.SocketChannelFactory;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandler;
import io.netty.channel.EventLoopGroup;

import java.net.InetSocketAddress;
import java.net.SocketAddress;

public class FakeDnsSocketChannelFactory implements SocketChannelFactory {
    private final DnsEngine fakeDns;
    private final SocketChannelFactory delegate;

    public FakeDnsSocketChannelFactory(final DnsEngine fakeDns, final SocketChannelFactory delegate) {
        this.fakeDns = fakeDns;
        this.delegate = delegate;
    }

    @Override
    public ChannelFuture open(final SocketAddress remoteAddress, final int connTimeoutMs, final boolean autoRead, final EventLoopGroup group, final ChannelHandler handler) {
        InetSocketAddress destination = (InetSocketAddress) remoteAddress;
        if (!destination.isUnresolved() && null != fakeDns) {
            final String domain = fakeDns.lookupX(destination.getAddress().getAddress());
            if (null != domain) {
                destination = InetSocketAddress.createUnresolved(domain, destination.getPort());
            } else if (destination.getAddress().getHostAddress().startsWith("198.18")) {
                throw new IllegalStateException(destination.getAddress().getHostAddress());
            }
        }
        return delegate.open(destination, connTimeoutMs, autoRead, group, handler);
    }
}
package com.github.pangolin.routing.beta.fakedns;

import com.github.pangolin.routing.handler.internal.server.support.DatagramChannelFactory;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandler;
import io.netty.channel.EventLoopGroup;

import java.net.InetSocketAddress;

public class FakeDnsDatagramChannelFactory implements DatagramChannelFactory {
    private final DnsEngine fakeDns;
    private final DatagramChannelFactory delegate;

    public FakeDnsDatagramChannelFactory(final DnsEngine fakeDns, final DatagramChannelFactory delegate) {
        this.fakeDns = fakeDns;
        this.delegate = delegate;
    }

    @Override
    public ChannelFuture open(final InetSocketAddress remoteAddress, final int connTimeoutMs, final EventLoopGroup group, final ChannelHandler handler) {
        InetSocketAddress destination = (InetSocketAddress) remoteAddress;
        if (!destination.isUnresolved()) {
            final String domain = fakeDns.lookupX(destination.getAddress().getAddress());
            if (null != domain) {
                destination = InetSocketAddress.createUnresolved(domain, destination.getPort());
            } else if (destination.getAddress().getHostAddress().startsWith("198.18")) {
                throw new IllegalStateException(destination.getAddress().getHostAddress());
            }
        }
        return delegate.open(destination, connTimeoutMs, group, handler);
    }
}
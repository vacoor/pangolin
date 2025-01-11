package com.github.pangolin.routing.extra.fakedns.support;

import com.github.pangolin.routing.extra.fakedns.DnsEngine;
import com.github.pangolin.routing.handler.internal.server.support.SocketChannelFactory;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandler;
import io.netty.channel.EventLoopGroup;
import io.netty.util.NetUtil;

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
        return delegate.open(resolve(remoteAddress), connTimeoutMs, autoRead, group, handler);
    }

    private SocketAddress resolve(final SocketAddress remoteAddress) {
        InetSocketAddress destination = (InetSocketAddress) remoteAddress;
        if (null != fakeDns) {
            byte[] address;
            if (destination.isUnresolved()) {
                final String hostString = destination.getHostString();
                address = NetUtil.createByteArrayFromIpAddressString(hostString);
            } else {
                address = destination.getAddress().getAddress();
            }

            if (null != address && fakeDns.isFake(address)) {
                final String hostname = fakeDns.resolve(address);
                if (null == hostname) {
                    throw new IllegalStateException(String.format("Fake-IP '%s' already release", NetUtil.bytesToIpAddress(address)));
                }
                return InetSocketAddress.createUnresolved(hostname, destination.getPort());
            }
        }
        return destination;
    }
}
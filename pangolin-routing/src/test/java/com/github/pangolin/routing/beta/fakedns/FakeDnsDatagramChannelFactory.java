package com.github.pangolin.routing.beta.fakedns;

import com.github.pangolin.routing.handler.internal.server.support.DatagramChannelFactory;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandler;
import io.netty.channel.EventLoopGroup;
import io.netty.util.NetUtil;

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
        return delegate.open(resolve(remoteAddress), connTimeoutMs, group, handler);
    }

    private InetSocketAddress resolve(final InetSocketAddress remoteAddress) {
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
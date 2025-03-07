package com.github.pangolin.routing.support;

import com.github.pangolin.routing.upstream.Upstream;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.socket.nio.NioDatagramChannel;
import io.netty.resolver.NoopAddressResolverGroup;
import io.netty.util.internal.ObjectUtil;
import lombok.extern.slf4j.Slf4j;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.Collections;
import java.util.List;

@Slf4j
public class ProxyDatagramChannelFactory implements DatagramChannelFactory {
    private final Upstream upstream;
    private final List<String> bypass;

    public ProxyDatagramChannelFactory(final Upstream upstream, final List<String> bypass) {
        this.upstream = ObjectUtil.checkNotNull(upstream, "upstream");
        this.bypass = null != bypass ? bypass : Collections.emptyList();
    }

    @Override
    public ChannelFuture open(final InetSocketAddress destination, final int connTimeoutMs, final EventLoopGroup group, final ChannelHandler handler) {
        final ChannelHandler transport = newDatagramProxyHandler(destination);
        final NoopAddressResolverGroup resolverGroup = null != transport ? NoopAddressResolverGroup.INSTANCE : null;

        // FIXME
        final Bootstrap b = new Bootstrap()
                .channel(NioDatagramChannel.class)
                .option(ChannelOption.SO_BROADCAST, false)
                .resolver(resolverGroup)
                .group(group)
                .handler(handler);
        return b.bind(0);
    }

    private ChannelHandler newDatagramProxyHandler(final SocketAddress destination) {
        if (destination instanceof InetSocketAddress) {
            final InetSocketAddress address = (InetSocketAddress) destination;
            final String hostname = address.isUnresolved() ? address.getHostString() : address.getHostName();
            if (!bypass.contains(hostname)) {
                return upstream.newDatagramProxyHandler(address);
            }
            log.info("[ROUTING] {}:{} will bypass the upstream", hostname, address.getPort());
        } else {
            log.debug("[ROUTING] UNSUPPORTED_ADDRESS {} will bypass the upstream", destination);
        }
        return null;
    }
}

package com.github.pangolin.routing.upstream;

import com.github.pangolin.routing.handler.internal.server.support.DatagramChannelFactory;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.socket.nio.NioDatagramChannel;
import io.netty.resolver.NoopAddressResolverGroup;
import lombok.extern.slf4j.Slf4j;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.Collections;
import java.util.List;

/**
 * @since 20240411
 */
@Slf4j
public class ProxyDatagramChannelFactory implements DatagramChannelFactory {
    private final UpstreamServer server;
    private final List<String> bypass;

    public ProxyDatagramChannelFactory(final UpstreamServer server, final List<String> bypass) {
        this.server = server;
        this.bypass = null != bypass ? bypass : Collections.emptyList();
    }

    @Override
    public ChannelFuture open(final InetSocketAddress destination, final int connTimeoutMs, final EventLoopGroup group, final ChannelHandler handler) {
        final UpstreamServer upstreamServer = choose(destination);
        ChannelHandler networkHandler = null != upstreamServer ? upstreamServer.newDatagramProxyHandler(destination) : null;
        final NoopAddressResolverGroup resolverGroup = null != networkHandler ? NoopAddressResolverGroup.INSTANCE : null;
        final Bootstrap b = new Bootstrap()
                .group(group)
                .channel(NioDatagramChannel.class)
                .resolver(resolverGroup)
                .option(ChannelOption.SO_BROADCAST, false)
                .handler(handler);
        return b.bind(0);
    }

    private UpstreamServer choose(final SocketAddress destinationAddress) {
        if (!(destinationAddress instanceof InetSocketAddress)) {
            log.info("[ROUTING] will bypass the upstream => {}", destinationAddress);
            return null;
        }
        final InetSocketAddress sa = (InetSocketAddress) destinationAddress;
        if ((sa.isUnresolved() && bypass.contains(sa.getHostString()))
                || (!sa.isUnresolved() && bypass.contains(sa.getHostName()))
        ) {
            log.info("[ROUTING] will bypass the upstream => {}:{}", sa.getHostString(), sa.getPort());
            return null;
        }

        return server;
    }

}

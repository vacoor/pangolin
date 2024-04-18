package com.github.pangolin.routing.proxy;

import com.github.pangolin.routing.handler.internal.server.support.SocketChannelFactory;
import com.github.pangolin.routing.rule.RulesProvider;
import com.github.pangolin.routing.rule.pattern.DestinationPattern;
import com.github.pangolin.util.Channels;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.resolver.NoopAddressResolverGroup;
import lombok.extern.slf4j.Slf4j;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * @since 20240411
 */
@Slf4j
public class SmartProxySocketChannelFactory implements SocketChannelFactory {
    private final RulesProvider rulesProvider;
    private final ProxyServerProvider proxyServerProvider;
    private final List<String> bypass;

    public SmartProxySocketChannelFactory(final RulesProvider rulesProvider, final ProxyServerProvider provider, final List<String> bypass) {
        this.rulesProvider = rulesProvider;
        this.proxyServerProvider = provider;
        this.bypass = null != bypass ? bypass : Collections.emptyList();
    }

    @Override
    public ChannelFuture open(final SocketAddress remoteAddress, final int connTimeoutMs, final boolean autoRead, final EventLoopGroup group, final ChannelHandler handler) {
        final ChannelHandler networkHandler = select(remoteAddress);
        final NoopAddressResolverGroup resolverGroup = null != networkHandler ? NoopAddressResolverGroup.INSTANCE : null;
        return Channels.open(remoteAddress, resolverGroup, connTimeoutMs, autoRead, group, new ChannelInitializer<SocketChannel>() {
            @Override
            protected void initChannel(final SocketChannel ch) throws Exception {
                if (null != networkHandler) {
                    ch.pipeline().addFirst(networkHandler);
                }
                ch.pipeline().addLast(handler);
            }
        });
    }

    private ChannelHandler select(final SocketAddress destinationAddress) {
        if (!(destinationAddress instanceof InetSocketAddress)) {
            log.info("[Route] {} using DIRECT", destinationAddress);
            return null;
        }
        final InetSocketAddress sa = (InetSocketAddress) destinationAddress;
        if ((sa.isUnresolved() && bypass.contains(sa.getHostString()))
                || (!sa.isUnresolved() && bypass.contains(sa.getHostName()))
        ) {
            log.info("[Route] {} using DIRECT", sa.getHostString());
            return null;
        }

        final Map<DestinationPattern, String> rules = rulesProvider.getRules();
        for (final Map.Entry<DestinationPattern, String> entry : rules.entrySet()) {
            if (!entry.getKey().matches(sa)) {
                continue;
            }

            log.info("[Route] {} using {}", sa.getHostString(), entry.getValue());
            final ProxyServer proxyToUse = proxyServerProvider.getInstance(entry.getValue());
            return null != proxyToUse ? proxyToUse.newProxyHandler(sa) : null;
        }

        log.info("[Route] {} using DIRECT", sa.getHostString());
        return null;
    }
}

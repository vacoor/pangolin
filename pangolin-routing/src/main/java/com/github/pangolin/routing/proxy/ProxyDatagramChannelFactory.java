package com.github.pangolin.routing.proxy;

import com.github.pangolin.routing.handler.internal.server.support.SocketChannelFactory;
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

/**
 * @since 20240411
 */
@Slf4j
public class ProxyDatagramChannelFactory implements SocketChannelFactory {
    private final ProxyServer server;
    private final List<String> bypass;

    public ProxyDatagramChannelFactory(final ProxyServer server, final List<String> bypass) {
        this.server = server;
        this.bypass = null != bypass ? bypass : Collections.emptyList();
    }

    @Override
    public ChannelFuture open(final SocketAddress remoteAddress, final int connTimeoutMs, final boolean autoRead, final EventLoopGroup group, final ChannelHandler handler) {
        final ProxyServer proxyServer = choose(remoteAddress);
        ChannelHandler networkHandler = null != proxyServer ? proxyServer.newDatagramProxyHandler((InetSocketAddress) remoteAddress) : null;
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

    private ProxyServer choose(final SocketAddress destinationAddress) {
        if (!(destinationAddress instanceof InetSocketAddress)) {
            log.info("[ROUTING] will bypass the proxy => {}", destinationAddress);
            return null;
        }
        final InetSocketAddress sa = (InetSocketAddress) destinationAddress;
        if ((sa.isUnresolved() && bypass.contains(sa.getHostString()))
                || (!sa.isUnresolved() && bypass.contains(sa.getHostName()))
        ) {
            log.info("[ROUTING] will bypass the proxy => {}:{}", sa.getHostString(), sa.getPort());
            return null;
        }

        return server;
    }
}

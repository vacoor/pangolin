package com.github.pangolin.routing.support;

import com.github.pangolin.routing.handler.internal.server.support.SocketChannelFactory;
import com.github.pangolin.routing.upstream.Upstream;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.*;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.resolver.NoopAddressResolverGroup;
import io.netty.util.internal.ObjectUtil;
import lombok.extern.slf4j.Slf4j;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.Collections;
import java.util.List;

@Slf4j
public class ProxySocketChannelFactory implements SocketChannelFactory {
    private final Upstream upstream;
    private final List<String> bypass;

    public ProxySocketChannelFactory(final Upstream upstream, final List<String> bypass) {
        this.upstream = ObjectUtil.checkNotNull(upstream, "upstream");
        this.bypass = null != bypass ? bypass : Collections.emptyList();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public ChannelFuture open(final SocketAddress destination, final int connTimeoutMs,
                              final boolean autoRead, final EventLoopGroup group, final ChannelHandler handler) {
        final ChannelHandler transport = newSocketProxyHandler(destination);
        final NoopAddressResolverGroup resolverGroup = null != transport ? NoopAddressResolverGroup.INSTANCE : null;

        final Bootstrap b = new Bootstrap();
        b.option(ChannelOption.AUTO_READ, autoRead);
        b.option(ChannelOption.TCP_NODELAY, true);
        b.option(ChannelOption.SO_KEEPALIVE, true);
        b.option(ChannelOption.CONNECT_TIMEOUT_MILLIS, connTimeoutMs);
        b.option(ChannelOption.SO_RCVBUF, 32 * 1024);// 读缓冲区为32k
        b.resolver(resolverGroup)
                .group(group)
                .channel(NioSocketChannel.class)
                .handler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(final SocketChannel ch) {
                        if (null != transport) {
                            ch.pipeline().addFirst(transport);
                        }
                        ch.pipeline().addLast(handler);
                    }
                });
        return b.connect(destination, new InetSocketAddress("10.188.71.3", 0));
    }

    private ChannelHandler newSocketProxyHandler(final SocketAddress destination) {
        if (destination instanceof InetSocketAddress) {
            final InetSocketAddress address = (InetSocketAddress) destination;
            final String hostname = address.isUnresolved() ? address.getHostString() : address.getHostName();
            if (!bypass.contains(hostname)) {
                return upstream.newSocketProxyHandler(address);
            }
            log.info("[ROUTING] {}:{} will bypass the upstream", hostname, address.getPort());
        } else {
            log.debug("[ROUTING] UNSUPPORTED_ADDRESS {} will bypass the upstream", destination);
        }
        return null;
    }

}

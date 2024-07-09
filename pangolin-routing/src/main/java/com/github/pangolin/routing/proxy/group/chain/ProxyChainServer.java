package com.github.pangolin.routing.proxy.group.chain;

import com.github.pangolin.routing.proxy.ProxyServer;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.socket.SocketChannel;

import java.net.InetSocketAddress;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

public class ProxyChainServer implements ProxyServer {
    private final String name;
    private final ProxyServer[] proxyServers;

    public ProxyChainServer(final String name, final ProxyServer... proxyServers) {
        this.name = name;
        this.proxyServers = proxyServers;
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public ChannelHandler newProxyHandler(final InetSocketAddress sa) {
        final List<ChannelHandler> handlers = Arrays.stream(proxyServers)
                .map(s -> this.newProxyHandler(s, sa))
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
        if (!handlers.isEmpty()) {
            return new ChannelInitializer<SocketChannel>() {
                @Override
                protected void initChannel(final SocketChannel ch) throws Exception {
                    ch.pipeline().addLast(handlers.toArray(new ChannelHandler[0]));
                }
            };
        }
        return null;
    }

    protected ChannelHandler newProxyHandler(final ProxyServer server, final InetSocketAddress sa) {
        return server.newProxyHandler(sa);
    }
}
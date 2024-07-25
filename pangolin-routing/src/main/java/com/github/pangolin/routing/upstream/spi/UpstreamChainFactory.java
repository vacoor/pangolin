package com.github.pangolin.routing.upstream.spi;

import com.github.pangolin.routing.upstream.Upstream;
import com.github.pangolin.routing.upstream.UpstreamCombiner;
import com.github.pangolin.routing.upstream.UpstreamRegistry;
import com.github.pangolin.routing.upstream.AbstractUpstream;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelInitializer;

import java.net.InetSocketAddress;
import java.util.stream.StreamSupport;

public class UpstreamChainFactory implements UpstreamCombiner {

    @Override
    public String name() {
        return "chain";
    }

    @Override
    public Upstream combine(final String name, final Iterable<String> names, final UpstreamRegistry registry) {
        return new AbstractUpstream(name) {
            @Override
            public ChannelHandler newSocketProxyHandler(final InetSocketAddress destination) {
                final ChannelHandler[] handlers = StreamSupport.stream(names.spliterator(), false)
                        .map(name -> lookupRequired(name, registry))
                        .map(upstream -> upstream.newSocketProxyHandler(destination))
                        .toArray(ChannelHandler[]::new);
                return createInitializer(handlers);
            }

            @Override
            public ChannelHandler newDatagramProxyHandler(final InetSocketAddress destination) {
                final ChannelHandler[] handlers = StreamSupport.stream(names.spliterator(), false)
                        .map(name -> lookupRequired(name, registry))
                        .map(upstream -> upstream.newDatagramProxyHandler(destination))
                        .toArray(ChannelHandler[]::new);
                return createInitializer(handlers);
            }

            @Override
            public String toString() {
                return name + "/chain," + names;
            }
        };
    }

    private Upstream lookupRequired(final String name, final UpstreamRegistry registry) {
        final Upstream upstream = registry.getUpstream(name);
        if (null == upstream) {
            throw new IllegalStateException(String.format("Upstream '%s' not found", name));
        }
        return upstream;
    }

    private <C extends Channel> ChannelInitializer<C> createInitializer(final ChannelHandler... handlers) {
        return new ChannelInitializer<C>() {
            @Override
            protected void initChannel(final C ch) throws Exception {
                ch.pipeline().addLast(handlers);
            }
        };
    }

}

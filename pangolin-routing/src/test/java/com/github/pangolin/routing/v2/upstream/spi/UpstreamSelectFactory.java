package com.github.pangolin.routing.v2.upstream.spi;

import com.github.pangolin.routing.v2.upstream.AbstractUpstream;
import com.github.pangolin.routing.v2.upstream.Upstream;
import com.github.pangolin.routing.v2.upstream.UpstreamCombiner;
import com.github.pangolin.routing.v2.upstream.UpstreamRegistry;
import io.netty.channel.ChannelHandler;

import java.net.InetSocketAddress;

public class UpstreamSelectFactory implements UpstreamCombiner {
    @Override
    public String name() {
        return "select";
    }

    @Override
    public Upstream combine(final String name, final Iterable<String> names, final UpstreamRegistry registry) {
        return new AbstractUpstream(name) {
            @Override
            public ChannelHandler newSocketProxyHandler(final InetSocketAddress destination) {
                return null;
            }

            @Override
            public ChannelHandler newDatagramProxyHandler(final InetSocketAddress destination) {
                return null;
            }

            @Override
            public String toString() {
                return name + "/select," + names;
            }
        };
    }
}

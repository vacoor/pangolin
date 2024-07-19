package com.github.pangolin.routing.v2.upstream;

import io.netty.channel.ChannelHandler;

import java.net.InetSocketAddress;

public class UpstreamServerSelectFactory implements UpstreamServerCombiner {
    @Override
    public String name() {
        return "select";
    }

    @Override
    public UpstreamServer combine(final String name, final Iterable<String> names, final UpstreamRegistry registry) {
        return new AbstractUpstreamServer(name) {
            @Override
            public ChannelHandler newSocketProxyHandler(final InetSocketAddress destination) {
                return null;
            }

            @Override
            public String toString() {
                return name + "/select," + names;
            }
        };
    }
}

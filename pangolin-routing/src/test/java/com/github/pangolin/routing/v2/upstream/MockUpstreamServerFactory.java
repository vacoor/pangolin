package com.github.pangolin.routing.v2.upstream;

import io.netty.channel.ChannelHandler;

import java.net.InetSocketAddress;

public class MockUpstreamServerFactory implements UpstreamServerFactory {
    @Override
    public boolean accept(final String url) {
        return true;
    }

    @Override
    public UpstreamServer apply(final String name, final String url) {
//            final URL urlToUse = new URL(url);
        return new AbstractUpstreamServer(name) {

            @Override
            public ChannelHandler newSocketProxyHandler(final InetSocketAddress destination) {
                return null;
            }

            @Override
            public String toString() {
                return name + "/" + url;
            }
        };
    }
}

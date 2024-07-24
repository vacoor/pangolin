package com.github.pangolin.routing.v2.upstream.spi;

import com.github.pangolin.routing.v2.upstream.AbstractUpstream;
import com.github.pangolin.routing.v2.upstream.Upstream;
import com.github.pangolin.routing.v2.upstream.UpstreamFactory;
import io.netty.channel.ChannelHandler;

import java.net.InetSocketAddress;

public class MockUpstreamFactory implements UpstreamFactory {
    @Override
    public boolean accept(final String url) {
        return true;
    }

    @Override
    public Upstream apply(final String name, final String url) {
//            final URL urlToUse = new URL(url);
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
                return name + "@" + url;
            }
        };
    }
}

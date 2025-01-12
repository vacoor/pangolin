package com.github.pangolin.routing.upstream.spi;

import com.github.pangolin.routing.upstream.AbstractUpstream;
import com.github.pangolin.routing.upstream.Upstream;
import io.netty.channel.ChannelHandler;

import java.net.InetSocketAddress;
import java.net.SocketAddress;

public class MockUpstreamFactory extends AbstractUpstreamFactory {

    public MockUpstreamFactory() {
        super(new String[]{"mock://"});
    }

    @Override
    protected Upstream apply0(final String name, final String serverUrl) {
        return new AbstractUpstream(name) {

            @Override
            public SocketAddress address() {
                return null;
            }

            @Override
            public boolean isVirtual() {
                return false;
            }

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
                return name + "@" + serverUrl;
            }
        };
    }
}

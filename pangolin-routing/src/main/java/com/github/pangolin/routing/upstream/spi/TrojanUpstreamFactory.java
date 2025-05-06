package com.github.pangolin.routing.upstream.spi;

import com.github.pangolin.routing.support.handler.client.TrojanDatagramProxyHandler;
import com.github.pangolin.routing.support.handler.client.TrojanProxyHandler;
import com.github.pangolin.routing.support.SocketChannelFactory;
import com.github.pangolin.routing.support.StandardSocketChannelFactory;
import com.github.pangolin.routing.upstream.Upstream;
import com.github.pangolin.routing.upstream.AbstractUpstream;
import io.netty.channel.ChannelHandler;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.URI;

/**
 * Trojan Proxy Server resolver.
 * <p>
 * URL format: trojan://{password}@{hostname}:{port}?{parameters}#{name}
 */
public class TrojanUpstreamFactory extends AbstractUpstreamFactory {
    private static final int DEFAULT_TROJAN_PORT = 443;

    public TrojanUpstreamFactory() {
        super(new String[]{"trojan://"});
    }

    @Override
    protected Upstream apply0(final String name, final String url) {
        // FIXME
        return resolve(name, url, new StandardSocketChannelFactory(null));
    }

    private Upstream resolve(final String name, final String serverUrl, final SocketChannelFactory factory) {
        /*-
         * URL format: trojan://{password}@{hostname}:{port}?{parameters}#{name}
         */
        final URI uri = URI.create(serverUrl);
        final String nameToUse = null != name ? name : uri.getFragment();
        final String password = uri.getUserInfo();

        final InetSocketAddress address = toSocketAddress(
                uri.getHost(),
                determinePort(uri.getPort())
        );

        final SocketChannelFactory factoryToUse = null != factory ? factory : new StandardSocketChannelFactory(null);
        return new TrojanUpstream(nameToUse, address, password, factoryToUse);
    }

    private int determinePort(final int port) {
        return port > 0 ? port : DEFAULT_TROJAN_PORT;
    }

    private class TrojanUpstream extends AbstractUpstream {
        private final InetSocketAddress address;
        private final String password;
        private final SocketChannelFactory socketChannelFactory;

        TrojanUpstream(final String name, final InetSocketAddress address, final String password, final SocketChannelFactory socketChannelFactory) {
            super(name);
            this.address = address;
            this.password = password;
            this.socketChannelFactory = socketChannelFactory;
        }

        @Override
        public SocketAddress address() {
            return address;
        }

        @Override
        public boolean isVirtual() {
            return false;
        }

        @Override
        public ChannelHandler newSocketProxyHandler(InetSocketAddress destination) {
            return new TrojanProxyHandler(address, password);
        }

        @Override
        public ChannelHandler newDatagramProxyHandler(final InetSocketAddress destination) {
            // FIXME
            return new TrojanDatagramProxyHandler(address, password, socketChannelFactory);
        }

        @Override
        public String toString() {
            return name + "/" + address;
        }
    }
}

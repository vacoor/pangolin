package com.github.pangolin.routing.proxy.spi;

import com.github.pangolin.routing.handler.internal.client.TrojanProxyHandler;
import com.github.pangolin.routing.proxy.AbstractServer;
import com.github.pangolin.routing.proxy.ProxyServer;
import io.netty.channel.ChannelHandler;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.URI;
import java.util.Properties;

/**
 * Trojan Proxy Server resolver.
 *
 * URL format: trojan://{password}@{hostname}:{port}?{parameters}#{name}
 */
public class TrojanServerResolver implements ServerResolver {
    private static final String URL_PREFIX = "trojan://";
    private static final int DEFAULT_PORT = 443;

    public boolean acceptsUrl(final String url) {
        return null != url && url.startsWith(URL_PREFIX);
    }

    /**
     *
     */
    public ProxyServer resolve(final String url, final Properties props) {
        if (this.acceptsUrl(url)) {
            final URI uri = URI.create(url);
            final String name = props.getProperty("name", uri.getFragment());
            final String password = uri.getUserInfo();

            final String host = uri.getHost();
            final int port = 0 < uri.getPort() ? uri.getPort() : DEFAULT_PORT;
            final InetSocketAddress address = Utils.toSocketAddress(host, port);
            return new TrojanServer(null != name ? name : host + ":" + port, address, password);
        }
        throw new UnsupportedOperationException();
    }

    /**
     *
     */
    private class TrojanServer extends AbstractServer  {
        private final SocketAddress address;
        private final String password;

        public TrojanServer(final String name, final SocketAddress address, final String password) {
            super(name);
            this.address = address;
            this.password = password;
        }

        @Override
        public String getName() {
            return name;
        }

        @Override
        public ChannelHandler newSocketProxyHandler(InetSocketAddress sa) {
            return new TrojanProxyHandler(address, password);
        }

        @Override
        public String toString() {
            return name + "/" + address;
        }
    }
}

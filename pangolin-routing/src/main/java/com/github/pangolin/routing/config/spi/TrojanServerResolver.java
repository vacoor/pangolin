package com.github.pangolin.routing.config.spi;

import com.github.pangolin.routing.handler.internal.client.TrojanProxyHandler;
import com.github.pangolin.routing.proxy.ProxyServer;
import io.netty.channel.ChannelHandler;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.URI;
import java.util.Properties;

/**
 *
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
        if (!acceptsUrl(url)) {
            return null;
        }
        final URI uri = URI.create(url);
        final String name = uri.getFragment();
        final String host = uri.getHost();
        final int port = 0 < uri.getPort() ? uri.getPort() : DEFAULT_PORT;
        final String password = uri.getUserInfo();
        return new Instance(null != name ? name : host + ":" + port, new InetSocketAddress(host, port), password);
    }

    /**
     *
     */
    private class Instance implements ProxyServer {
        private final String name;
        private final SocketAddress address;
        private final String password;

        public Instance(final String name, final SocketAddress address, final String password) {
            this.name = name;
            this.address = address;
            this.password = password;
        }

        @Override
        public String getName() {
            return name;
        }

        @Override
        public ChannelHandler newProxyHandler(InetSocketAddress sa) {
            return new TrojanProxyHandler(address, password);
        }

        @Override
        public String toString() {
            return name + "/" + address;
        }
    }
}

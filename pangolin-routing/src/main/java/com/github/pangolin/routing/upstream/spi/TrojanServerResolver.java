package com.github.pangolin.routing.upstream.spi;

import com.github.pangolin.routing.handler.internal.client.TrojanDatagramProxyHandler;
import com.github.pangolin.routing.handler.internal.client.TrojanProxyHandler;
import com.github.pangolin.routing.handler.internal.server.support.SocketChannelFactory;
import com.github.pangolin.routing.handler.internal.server.support.StandardSocketChannelFactory;
import com.github.pangolin.routing.upstream.AbstractServer;
import com.github.pangolin.routing.upstream.UpstreamServer;
import com.github.pangolin.routing.upstream.UpstreamServerResolver;
import com.github.pangolin.routing.util.SocketUtils;
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
public class TrojanServerResolver implements UpstreamServerResolver {
    private static final String URL_PREFIX = "trojan://";
    private static final int DEFAULT_PORT = 443;

    public boolean acceptsUrl(final String url) {
        return null != url && url.startsWith(URL_PREFIX);
    }

    /**
     *
     */
    public UpstreamServer resolve(final String url, final Properties props) {
        return resolve(new StandardSocketChannelFactory(), url, props);
    }

    public UpstreamServer resolve(final SocketChannelFactory socketChannelFactory, final String url, final Properties props) {
        if (this.acceptsUrl(url)) {
            final URI uri = URI.create(url);
            final String name = props.getProperty("name", uri.getFragment());
            final String password = uri.getUserInfo();

            final String host = uri.getHost();
            final int port = 0 < uri.getPort() ? uri.getPort() : DEFAULT_PORT;
            final InetSocketAddress address = SocketUtils.toSocketAddress(host, port);
            SocketChannelFactory factory = null != socketChannelFactory ? socketChannelFactory : new StandardSocketChannelFactory();
            return new TrojanServer(null != name ? name : host + ":" + port, address, password, factory);
        }
        throw new UnsupportedOperationException();
    }

    /**
     *
     */
    private class TrojanServer extends AbstractServer  {
        private final InetSocketAddress address;
        private final String password;
        private final SocketChannelFactory socketChannelFactory;

        public TrojanServer(final String name, final InetSocketAddress address, final String password, final SocketChannelFactory socketChannelFactory) {
            super(name);
            this.address = address;
            this.password = password;
            this.socketChannelFactory = socketChannelFactory;
        }

        @Override
        public String getName() {
            return name;
        }

        @Override
        public ChannelHandler newSocketProxyHandler(InetSocketAddress destination) {
            return new TrojanProxyHandler(address, password);
        }

        @Override
        public ChannelHandler newDatagramProxyHandler(final InetSocketAddress destination) {
            return new TrojanDatagramProxyHandler(address, password, socketChannelFactory);
        }

        @Override
        public String toString() {
            return name + "/" + address;
        }
    }
}

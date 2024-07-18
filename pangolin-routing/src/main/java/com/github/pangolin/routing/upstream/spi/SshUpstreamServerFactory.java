package com.github.pangolin.routing.upstream.spi;

import com.github.pangolin.routing.handler.internal.client.SshProxyHandler;
import com.github.pangolin.routing.upstream.AbstractUpstreamServer;
import com.github.pangolin.routing.upstream.UpstreamServer;
import com.github.pangolin.routing.upstream.UpstreamServerFactory;
import freework.codec.Base64;
import freework.util.Bytes;
import io.netty.channel.ChannelHandler;

import java.net.InetSocketAddress;
import java.net.URI;
import java.util.Properties;

/**
 *
 */
public class SshUpstreamServerFactory implements UpstreamServerFactory {
    private static final String URL_PREFIX = "ssh://";
    private static final int DEFAULT_PORT = 22;

    @Override
    public boolean accept(final String url) {
        return null != url && url.startsWith(URL_PREFIX);
    }

    /**
     *
     */
    @Override
    public UpstreamServer create(final String url, final Properties props) {
        if (!accept(url)) {
            return null;
        }
        final URI uri = URI.create(url);
        final String name = props.getProperty("name", uri.getFragment());
        final String host = uri.getHost();
        final int port = 0 < uri.getPort() ? uri.getPort() : DEFAULT_PORT;
        final String userInfo = resolveUserInfo(uri.getUserInfo());
        if (null == userInfo) {
            throw new IllegalStateException("SSH username & password must be not null");
        }
        final String[] segments = userInfo.split(":", 2);
        final String username = segments[0];
        final String password = segments.length < 2 ? "" : segments[1];
        return new SshServer(name, host, port, username, password);
    }

    private String resolveUserInfo(final String userInfo) {
        /*-
         * With user info encoded with Base64URL.
         */
        if (null != userInfo && !userInfo.contains(":")) {
            return Bytes.toString(Base64.decode(userInfo, true));
        }
        return userInfo;
    }

    class SshServer extends AbstractUpstreamServer {
        private final String hostname;
        private final int port;
        private final String username;
        private final String password;

        SshServer(final String name,
                  final String hostname, final int port,
                  final String username, final String password) {
            super(name);
            this.hostname = hostname;
            this.port = port;
            this.username = username;
            this.password = password;
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public ChannelHandler newSocketProxyHandler(final InetSocketAddress destination) {
            return new SshProxyHandler(hostname, port, username, password);
        }

    }
}

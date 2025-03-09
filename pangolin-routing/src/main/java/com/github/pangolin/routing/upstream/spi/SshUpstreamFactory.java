package com.github.pangolin.routing.upstream.spi;

import com.github.pangolin.routing.handler.client.SshProxyHandler;
import com.github.pangolin.routing.upstream.Upstream;
import com.github.pangolin.routing.upstream.AbstractUpstream;
import io.netty.channel.ChannelHandler;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.URI;

public class SshUpstreamFactory extends AbstractUpstreamFactory {
    private static final int DEFAULT_SSH_PORT = 22;

    public SshUpstreamFactory() {
        super(new String[]{"ssh://"});
    }

    @Override
    protected Upstream apply0(final String name, final String serverUrl) {
        final URI uri = URI.create(serverUrl);
        final String nameToUse = null != name ? name : uri.getFragment();

        final String[] userInfo = splitUserInfo(uri.getUserInfo());
        if (2 != userInfo.length) {
            throw new IllegalArgumentException("SSH username & password must not be null");
        }

        return new SshUpstream(nameToUse, uri.getHost(), determinePort(uri.getPort()), userInfo[0], userInfo[1]);
    }

    private int determinePort(final int port) {
        return port > 0 ? port : DEFAULT_SSH_PORT;
    }

    private class SshUpstream extends AbstractUpstream {
        private final String hostname;
        private final int port;
        private final String username;
        private final String password;

        SshUpstream(final String name,
                    final String hostname, final int port,
                    final String username, final String password) {
            super(name);
            this.hostname = hostname;
            this.port = port;
            this.username = username;
            this.password = password;
        }

        @Override
        public SocketAddress address() {
            return new InetSocketAddress(hostname, port);
        }

        @Override
        public boolean isVirtual() {
            return false;
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public ChannelHandler newSocketProxyHandler(final InetSocketAddress destination) {
            return new SshProxyHandler(hostname, port, username, password);
        }

        @Override
        public ChannelHandler newDatagramProxyHandler(final InetSocketAddress destination) {
            return null;
        }

    }
}

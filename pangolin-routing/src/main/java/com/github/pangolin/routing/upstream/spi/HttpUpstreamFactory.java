package com.github.pangolin.routing.upstream.spi;

import com.github.pangolin.routing.upstream.Upstream;
import com.github.pangolin.routing.upstream.AbstractUpstream;
import io.netty.channel.ChannelHandler;
import io.netty.handler.codec.http.DefaultHttpHeaders;
import io.netty.handler.proxy.HttpProxyHandler;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.URI;

public class HttpUpstreamFactory extends AbstractUpstreamFactory {
    private static final String HTTPS = "https";
    private static final String HTTP_PREFIX = "http://";
    private static final String HTTPS_PREFIX = "https://";

    private static final int DEFAULT_HTTP_PORT = 80;
    private static final int DEFAULT_HTTPS_PORT = 443;

    public HttpUpstreamFactory() {
        // TODO complete https support
        super(new String[]{HTTP_PREFIX});
    }

    @Override
    protected Upstream apply0(final String name, final String serverUrl) {
        final URI uri = URI.create(serverUrl);
        final String nameToUse = null != name ? name : uri.getFragment();
        final boolean isSecure = HTTPS.equalsIgnoreCase(uri.getScheme());

        final InetSocketAddress address = toSocketAddress(
                uri.getHost(),
                determinePort(uri.getPort(), isSecure)
        );

        final String[] userInfo = splitUserInfo(uri.getUserInfo());
        final String username = userInfo.length > 0 ? userInfo[0] : null;
        final String password = userInfo.length > 1 ? userInfo[1] : null;

        return new HttpUpstream(nameToUse, address, username, password);
    }

    private int determinePort(final int port, final boolean isSecure) {
        return port > 0 ? port : (isSecure ? DEFAULT_HTTPS_PORT : DEFAULT_HTTP_PORT);
    }

    private class HttpUpstream extends AbstractUpstream {
        private final SocketAddress address;
        private final String username;
        private final String password;

        HttpUpstream(final String name, final SocketAddress address, final String username, final String password) {
            super(name);
            this.address = address;
            this.username = username;
            this.password = password;
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
            final DefaultHttpHeaders headers = new DefaultHttpHeaders();
            /*-
             * FIXED 499
            headers.add("Proxy-Connection", "keep-alive")
                   .add("User-Agent", "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36");
             */

            if (null == username || null == password) {
                return new HttpProxyHandler(address, headers, false);
            }
            return new HttpProxyHandler(address, username, password, headers, false);
        }

        @Override
        public ChannelHandler newDatagramProxyHandler(final InetSocketAddress destination) {
            return null;
        }
    }
}

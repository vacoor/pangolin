package com.github.pangolin.routing.upstream.spi;

import com.github.pangolin.routing.upstream.AbstractServer;
import com.github.pangolin.routing.upstream.UpstreamServer;
import com.github.pangolin.routing.upstream.UpstreamServerResolver;
import com.github.pangolin.routing.util.SocketUtils;
import freework.codec.Base64;
import freework.util.Bytes;
import io.netty.channel.ChannelHandler;
import io.netty.handler.codec.http.DefaultHttpHeaders;
import io.netty.handler.proxy.HttpProxyHandler;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.URI;
import java.util.Properties;

/**
 *
 */
public class HttpServerResolver implements UpstreamServerResolver {
    private static final String URL_PREFIX = "http://";
    private static final int DEFAULT_PORT = 80;

    public boolean acceptsUrl(final String url) {
        return null != url && url.startsWith(URL_PREFIX);
    }

    /**
     */
    public UpstreamServer resolve(final String url, final Properties props) {
        if (!acceptsUrl(url)) {
            return null;
        }
        final URI uri = URI.create(url);
        final String name = props.getProperty("name", uri.getFragment());
        final String host = uri.getHost();
        final int port = 0 < uri.getPort() ? uri.getPort() : DEFAULT_PORT;

        final InetSocketAddress address = SocketUtils.toSocketAddress(host, port);

        final String userInfo = resolveUserInfo(uri.getUserInfo());
        if (null != userInfo) {
            final String[] segments = userInfo.split(":", 2);
            final String username = segments[0];
            final String password = segments.length < 2 ? "" : segments[1];
            return new HttpServer(name, address, username, password);
        }

        return new HttpServer(name, address, null, null);
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

    /**
     *
     */
    private class HttpServer extends AbstractServer {
        private final SocketAddress address;
        private final String username;
        private final String password;

        public HttpServer(final String name, final SocketAddress address, final String username, final String password) {
            super(name);
            this.address = address;
            this.username = username;
            this.password = password;
        }

        @Override
        public String getName() {
            return name;
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
    }
}

package com.github.pangolin.routing.proxy.spi;

import com.github.pangolin.routing.handler.internal.client.SsProxyHandler;
import com.github.pangolin.routing.handler.codec.ss.crypto.CipherAlgorithm;
import com.github.pangolin.routing.handler.codec.ss.crypto.spi.CipherAlgorithmSpi;
import com.github.pangolin.routing.proxy.ProxyServer;
import freework.codec.Base64;
import freework.util.Bytes;
import io.netty.channel.ChannelHandler;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.URI;
import java.util.Properties;

/**
 *
 */
public class SsServerResolver  implements ServerResolver {
    private static final String URL_PREFIX = "ss://";
    private static final int DEFAULT_PORT = 1080;

    public boolean acceptsUrl(final String url) {
        return null != url && url.startsWith(URL_PREFIX);
    }

    /**
     * <pre>
     * SS-URI = "ss://" userinfo "@" hostname ":" port [ "/" ] [ "?" plugin ] [ "#" tag ]
     * userinfo = websafe-base64-encode-utf8(method  ":" password)
     *            method ":" password
     * </pre>
     * @see <a href="#">SIP002 URI Scheme</a>
     */
    public ProxyServer resolve(final String url, final Properties props) {
        if (!acceptsUrl(url)) {
            return null;
        }
        final URI uri = URI.create(url);
        final String name = props.getProperty("name", uri.getFragment());
        final String host = uri.getHost();
        final int port = 0 < uri.getPort() ? uri.getPort() : DEFAULT_PORT;
        final String userInfo = resolveUserInfo(uri.getUserInfo());
        final String[] segments = userInfo.split(":", 2);

        final String method = segments[0];
        final String password = segments.length < 2 ? "" : segments[1];

        final CipherAlgorithm algorithm = CipherAlgorithmSpi.getInstance(method);
        return new Instance(name, new InetSocketAddress(host, port), algorithm, password);
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
    private class Instance implements ProxyServer {
        private final String name;
        private final SocketAddress address;
        private final CipherAlgorithm algorithm;
        private final String password;

        public Instance(final String name, final SocketAddress address, final CipherAlgorithm algorithm, final String password) {
            this.name = name;
            this.address = address;
            this.algorithm = algorithm;
            this.password = password;
        }

        @Override
        public String getName() {
            return name;
        }

        @Override
        public ChannelHandler newProxyHandler(InetSocketAddress sa) {
            return new SsProxyHandler(address, algorithm, password);
        }
    }
}

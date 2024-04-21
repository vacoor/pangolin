package com.github.pangolin.routing.beta.ssh;

import com.github.pangolin.routing.proxy.ProxyServer;
import com.github.pangolin.routing.proxy.spi.ServerResolver;
import freework.codec.Base64;
import freework.util.Bytes;

import java.net.URI;
import java.util.Properties;

/**
 *
 */
public class SshServerResolver implements ServerResolver {
    private static final String URL_PREFIX = "ssh://";
    private static final int DEFAULT_PORT = 22;

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
        return new SshProxyServer(name, host, port, username, password);
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
}

package com.github.pangolin.routing.upstream.spi;

import com.github.pangolin.routing.handler.internal.client.WebSocketProxyHandler;
import com.github.pangolin.routing.upstream.AbstractServer;
import com.github.pangolin.routing.upstream.UpstreamServer;
import com.github.pangolin.routing.upstream.UpstreamServerResolver;
import io.netty.channel.ChannelHandler;

import java.net.InetSocketAddress;
import java.net.URI;
import java.util.Properties;

/**
 *
 */
public class WebSocketServerResolver implements UpstreamServerResolver {
    public boolean acceptsUrl(final String url) {
        if (null == url) {
            return false;
        }
        return url.startsWith("ws://") || url.startsWith("wss://");
    }

    /**
     *
     */
    public UpstreamServer resolve(final String url, final Properties props) {
        if (!acceptsUrl(url)) {
            return null;
        }
        int i = url.indexOf("#");
        final String name = 0 < i ? url.substring(i + 1) : url;
        final String nameToUse = props.getProperty("name", name);
        final URI uri = URI.create(0 < i ? url.substring(0, i) : url);
        return new WebSocketServer(null != nameToUse ? nameToUse : url, uri);
    }

    /**
     *
     */
    private class WebSocketServer extends AbstractServer {
        private final URI uri;

        public WebSocketServer(final String name, final URI uri) {
            super(name);
            this.uri = uri;
        }

        @Override
        public String getName() {
            return name;
        }

        @Override
        public ChannelHandler newSocketProxyHandler(InetSocketAddress sa) {
            return new WebSocketProxyHandler(uri, null);
        }

        @Override
        public String toString() {
            return name + "/" + uri;
        }
    }
}

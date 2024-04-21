package com.github.pangolin.routing.proxy.spi;

import com.github.pangolin.routing.handler.internal.client.WebSocketProxyHandler;
import com.github.pangolin.routing.proxy.ProxyServer;
import io.netty.channel.ChannelHandler;

import java.net.InetSocketAddress;
import java.net.URI;
import java.util.Properties;

/**
 *
 */
public class WebSocketServerResolver implements ServerResolver {
    public boolean acceptsUrl(final String url) {
        if (null == url) {
            return false;
        }
        return url.startsWith("ws://") || url.startsWith("wss://");
    }

    /**
     *
     */
    public ProxyServer resolve(final String url, final Properties props) {
        if (!acceptsUrl(url)) {
            return null;
        }
        int i = url.indexOf("#");
        final String name = 0 < i ? url.substring(i + 1) : url;
        final String nameToUse = props.getProperty("name", name);
        final URI uri = URI.create(0 < i ? url.substring(0, i) : url);
        return new Instance(null != nameToUse ? nameToUse : url, uri);
    }

    /**
     *
     */
    private class Instance implements ProxyServer {
        private final String name;
        private final URI uri;

        public Instance(final String name, final URI uri) {
            this.name = name;
            this.uri = uri;
        }

        @Override
        public String getName() {
            return name;
        }

        @Override
        public ChannelHandler newProxyHandler(InetSocketAddress sa) {
            return new WebSocketProxyHandler(uri, null);
        }

        @Override
        public String toString() {
            return name + "/" + uri;
        }
    }
}

package com.github.pangolin.routing.upstream.spi;

import com.github.pangolin.routing.handler.internal.client.WebSocketProxyHandler;
import com.github.pangolin.routing.upstream.Upstream;
import com.github.pangolin.routing.upstream.AbstractUpstream;
import io.netty.channel.ChannelHandler;

import java.net.InetSocketAddress;
import java.net.URI;

/**
 *
 */
public class WebSocketUpstreamFactory extends AbstractUpstreamFactory {
    private static final String WSS = "wss";
    private static final String WS_PREFIX = "ws://";
    private static final String WSS_PREFIX = "wss://";

    public WebSocketUpstreamFactory() {
        // FIXME WSS
        super(new String[]{WS_PREFIX});
    }

    @Override
    protected Upstream apply0(final String name, final String serverUrl) {
        final URI uri = URI.create(serverUrl);
        final String nameToUse = null != name ? name : uri.getFragment();
        // FIXME WSS
        final boolean isSecure = WSS.equalsIgnoreCase(uri.getScheme());

        final int index = serverUrl.indexOf("#");
        final URI uriToUse = URI.create(0 < index ? serverUrl.substring(0, index) : serverUrl);
        return new WebSocketUpstream(nameToUse, uriToUse);
    }

    private class WebSocketUpstream extends AbstractUpstream {
        private final URI uri;

        public WebSocketUpstream(final String name, final URI uri) {
            super(name);
            this.uri = uri;
        }

        @Override
        public String getName() {
            return name;
        }

        @Override
        public ChannelHandler newSocketProxyHandler(InetSocketAddress destination) {
            return new WebSocketProxyHandler(uri, null);
        }

        @Override
        public ChannelHandler newDatagramProxyHandler(final InetSocketAddress destination) {
            return null;
        }

        @Override
        public String toString() {
            return name + "/" + uri;
        }
    }
}

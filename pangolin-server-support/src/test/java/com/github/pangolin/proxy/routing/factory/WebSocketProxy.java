package com.github.pangolin.proxy.routing.factory;

import com.github.pangolin.proxy.client.WebSocketProxyHandler2;
import io.netty.channel.ChannelHandler;

import java.net.URI;

/**
 *
 */
public class WebSocketProxy implements Proxy {
    private final URI endpoint;
    private final String protocol;


    public WebSocketProxy(final String endpoint, final String protocol) {
        this(URI.create(endpoint), protocol);
    }

    public WebSocketProxy(final URI endpoint, final String protocol) {
        this.endpoint = endpoint;
        this.protocol = protocol;
    }

    @Override
    public ChannelHandler newProxyHandler() {
        return new WebSocketProxyHandler2(endpoint, protocol);
        // return new WebSocketProxyClientHandler2(endpoint, protocol);
    }
}

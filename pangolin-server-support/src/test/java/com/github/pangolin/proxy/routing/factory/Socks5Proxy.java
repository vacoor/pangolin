package com.github.pangolin.proxy.routing.factory;

import com.github.pangolin.proxy.client.Socks5ProxyClientHandler;
import io.netty.channel.ChannelHandler;

import java.net.InetSocketAddress;
import java.net.SocketAddress;

/**
 * TODO DOC ME!.
 *
 * @author changhe.yang
 * @since 20230905
 */
public class Socks5Proxy implements Proxy {
    private final SocketAddress proxyAddress;

    public Socks5Proxy(final String hostname, final int port) {
        this(InetSocketAddress.createUnresolved(hostname, port));
    }

    public Socks5Proxy(final SocketAddress proxyAddress) {
        this.proxyAddress = proxyAddress;
    }

    @Override
    public ChannelHandler newProxyHandler() {
        return new Socks5ProxyClientHandler(proxyAddress);
    }
}

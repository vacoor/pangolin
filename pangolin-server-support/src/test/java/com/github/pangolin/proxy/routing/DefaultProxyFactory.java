package com.github.pangolin.proxy.routing;

import com.github.pangolin.proxy.client.Socks5ProxyClientHandler;
import io.netty.channel.ChannelHandler;

import java.net.InetSocketAddress;
import java.net.URI;

/**
 * TODO DOC ME!.
 *
 * @author changhe.yang
 * @since 20230904
 */
public class DefaultProxyFactory implements ProxyFactory{
    @Override
    public ChannelHandler newProxyHandler(final URI proxyServerUri) {
        final String scheme = proxyServerUri.getScheme();
        if ("socks5".equals(scheme)) {
            return new Socks5ProxyClientHandler(new InetSocketAddress(proxyServerUri.getHost(), proxyServerUri.getPort()));
        }
        return null;
    }
}

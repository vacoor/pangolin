package com.github.pangolin.routing.internal.proxy.lb;

import com.github.pangolin.routing.internal.proxy.ProxyServer2;
import io.netty.channel.ChannelHandler;

import java.net.InetSocketAddress;

public abstract class LoadBalanceProxyServer implements ProxyServer2 {

    @Override
    public ChannelHandler newProxyHandler(final InetSocketAddress sa) {
        final ProxyServer2 proxyToUse = choose(sa);
        return null != proxyToUse ? proxyToUse.newProxyHandler(sa) : null;
    }

    protected abstract ProxyServer2 choose(final InetSocketAddress sa);

}
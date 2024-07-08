package com.github.pangolin.routing.proxy.group.lb;

import com.github.pangolin.routing.proxy.ProxyServer;
import io.netty.channel.ChannelHandler;
import lombok.extern.slf4j.Slf4j;

import java.net.InetSocketAddress;
import java.util.List;

@Slf4j
public class LoadBalanceProxyServer2 implements ProxyServer {
    private final String name;
    private final String type;
    private final String url;

    public LoadBalanceProxyServer2(final String name, final String type, final String url, final List<ProxyServer> servers) {
        this.name = name;
        this.type = type;
        this.url = url;
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public ChannelHandler newProxyHandler(final InetSocketAddress sa) {
        return null;
    }

}
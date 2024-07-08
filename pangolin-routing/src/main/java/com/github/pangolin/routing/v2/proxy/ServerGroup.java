package com.github.pangolin.routing.v2.proxy;

import com.github.pangolin.routing.proxy.ProxyServer;
import io.netty.channel.ChannelHandler;

import java.net.InetSocketAddress;
import java.util.List;

public class ServerGroup implements ProxyServer {
    private final String name;
    private final List<ProxyServer> instances;

    public ServerGroup(final String name, final List<ProxyServer> instances) {
        this.name = name;
        this.instances = instances;
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public ChannelHandler newProxyHandler(final InetSocketAddress sa) {
        return instances.iterator().next().newProxyHandler(sa);
    }

}
package com.github.pangolin.routing.v2.proxy;

import com.github.pangolin.routing.proxy.ProxyServer;
import io.netty.channel.ChannelHandler;

import java.net.InetSocketAddress;
import java.util.List;
import java.util.Random;

public class ServerGroup implements ProxyServer {
    private final String name;
    private final String type;
    private final List<ProxyServer> instances;

    public ServerGroup(final String name, final String type, final List<ProxyServer> instances) {
        this.name = name;
        this.type = type;
        this.instances = instances;
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public ChannelHandler newProxyHandler(final InetSocketAddress sa) {
        final int size = instances.size();
//        return 0 < size ? instances.get(new Random().nextInt(size)).newProxyHandler(sa) : null;
        if (0 < size) {
            final ProxyServer next = instances.iterator().next();
            return next.newProxyHandler(sa);
        }
        return null;
    }

}
package com.github.pangolin.routing.proxy;

import com.github.pangolin.routing.proxy.health.HealthChecker;
import io.netty.channel.ChannelHandler;
import lombok.extern.slf4j.Slf4j;

import java.net.InetSocketAddress;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;

@Slf4j
public class LoadBalanceProxyServer implements ProxyServer {
    private final String name;
    private final LoadBalancer lb;

    public LoadBalanceProxyServer(final String name, final HealthChecker healthChecker,
                                  final List<ProxyServer> servers, final ScheduledExecutorService scheduler) {
        this.name = name;
        this.lb = new LoadBalancer(name, healthChecker, servers, scheduler);
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public ChannelHandler newProxyHandler(final InetSocketAddress sa) {
        final ProxyServer next = lb.next(true);
        double serverAvgRt = lb.getServerAvgRt(next);
        log.info("{} -> {} -> {}: {}ms", sa, getName(), next.getName(), serverAvgRt);
        return next.newProxyHandler(sa);
    }

}
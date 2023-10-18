package com.github.pangolin.routing.internal.node;

import com.baozun.proxy.ProxyServer;
import io.netty.channel.ChannelHandler;
import lombok.AllArgsConstructor;
import lombok.Getter;

import java.net.InetSocketAddress;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public abstract class RuleBasedRoutingProxyServer implements ProxyServer {
    private final String name;
    private final ConcurrentMap<String, Routing> registeredRoutingMap = new ConcurrentHashMap<>();

    public RuleBasedRoutingProxyServer(final String name) {
        this.name = name;
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public ChannelHandler newProxyHandler(final InetSocketAddress sa) {
        final Set<String> proxies = new HashSet<>();
        for (final Routing rule : registeredRoutingMap.values()) {
            if (rule.pattern.matches(sa)) {
                proxies.add(rule.proxy);
            }
        }
        final ProxyServer proxyToUse = !proxies.isEmpty() ? choose(sa, proxies) : null;
        return null != proxyToUse ? proxyToUse.newProxyHandler(sa) : null;
    }

    protected abstract ProxyServer choose(final InetSocketAddress sa, final Set<String> proxies);

    public String addRouting(final DestinationPattern pattern, final String proxy) {
        final String id = UUID.randomUUID().toString();
        registeredRoutingMap.put(id, new Routing(id, pattern, proxy));
        return id;
    }

    public void removeRouting(final String routingId) {
        registeredRoutingMap.remove(routingId);
    }

    @Getter
    @AllArgsConstructor
    public static class Routing {
        private final String id;
        private final DestinationPattern pattern;
        private final String proxy;
    }
}

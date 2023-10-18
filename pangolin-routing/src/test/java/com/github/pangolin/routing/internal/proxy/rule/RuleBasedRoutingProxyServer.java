package com.github.pangolin.routing.internal.proxy.rule;

import com.github.pangolin.routing.internal.proxy.ProxyServer2;
import com.github.pangolin.routing.internal.proxy.ProxyServerStats;
import com.github.pangolin.routing.internal.proxy.health.HealthCheckScheduler;
import com.github.pangolin.routing.pattern.DestinationPattern;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import io.netty.channel.ChannelHandler;
import lombok.AllArgsConstructor;
import lombok.Getter;

import java.net.InetSocketAddress;
import java.util.*;
import java.util.concurrent.*;

public abstract class RuleBasedRoutingProxyServer implements ProxyServer2 {
    private final String name;
    private final HealthCheckScheduler healthCheckScheduler;

    private final ConcurrentMap<String, Routing> registeredRoutingMap = new ConcurrentHashMap<>();
    private final LoadingCache<String, ProxyServerStats> statsCache = CacheBuilder.newBuilder()
            .expireAfterAccess(10, TimeUnit.MINUTES)
            .build(new CacheLoader<String, ProxyServerStats>() {
                @Override
                public ProxyServerStats load(final String key) throws Exception {
                    return new ProxyServerStats(name);
                }
            });

    public RuleBasedRoutingProxyServer(final String name, final HealthCheckScheduler healthCheckScheduler) {
        this.name = name;
        this.healthCheckScheduler = healthCheckScheduler;
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
        final ProxyServer2 proxyToUse = !proxies.isEmpty() ? choose(sa, proxies) : null;
        return null != proxyToUse ? proxyToUse.newProxyHandler(sa) : null;
    }

    protected ProxyServer2 choose(final InetSocketAddress sa, final Set<String> proxies) {
        try {
            final Map<String, ProxyServerStats> statsMap = statsCache.getAll(proxies);
            final List<ProxyServerStats> stats = new ArrayList<>(statsMap.values());
            stats.sort(new Comparator<ProxyServerStats>() {
                @Override
                public int compare(final ProxyServerStats o1, final ProxyServerStats o2) {
                    return Double.compare(o1.getResponseTimeAvg(), o2.getResponseTimeAvg());
                }
            });
            // FIXME
            return null;
            // return stats.get(ThreadLocalRandom.current().nextInt(Math.min(upServers.size(), 10)));
        } catch (ExecutionException e) {
            return null;
        }
    }

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

    public static void main(String[] args) throws ExecutionException {
        final LoadingCache<String, ProxyServerStats> statsCache = CacheBuilder.newBuilder()
                .expireAfterAccess(10, TimeUnit.MINUTES)
                .build(new CacheLoader<String, ProxyServerStats>() {
                    @Override
                    public ProxyServerStats load(final String key) throws Exception {
                        return new ProxyServerStats(key);
                    }
                });
        ProxyServerStats xx = statsCache.get("xx");
        System.out.println(xx);
    }
}

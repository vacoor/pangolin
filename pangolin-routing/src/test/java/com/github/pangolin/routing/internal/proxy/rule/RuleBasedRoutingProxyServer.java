package com.github.pangolin.routing.internal.proxy.rule;

import com.github.pangolin.routing.internal.proxy.ProxyServer2;
import com.github.pangolin.routing.internal.proxy.ProxyServerProvider;
import com.github.pangolin.routing.internal.proxy.ProxyServerStats;
import com.github.pangolin.routing.pattern.DestinationPattern;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import io.netty.channel.ChannelHandler;

import java.net.InetSocketAddress;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

public class RuleBasedRoutingProxyServer implements ProxyServer2 {
    private final String name;

    private final ProxyServerProvider proxyServerProvider;
    private final ConcurrentMap<DestinationPattern, String> registeredRoutingMap = new ConcurrentHashMap<>();
    /*
    private final LoadingCache<String, ProxyServerStats> statsCache = CacheBuilder.newBuilder()
            .expireAfterAccess(10, TimeUnit.MINUTES)
            .build(new CacheLoader<String, ProxyServerStats>() {
                @Override
                public ProxyServerStats load(final String key) throws Exception {
                    return new ProxyServerStats(name);
                }
            });
            */

    public RuleBasedRoutingProxyServer(final String name, final ProxyServerProvider provider) {
        this.name = name;
        this.proxyServerProvider = provider;
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public ChannelHandler newProxyHandler(final InetSocketAddress sa) {
        for (final Map.Entry<DestinationPattern, String> entry : registeredRoutingMap.entrySet()) {
            if (!entry.getKey().matches(sa)) {
                continue;
            }
            final ProxyServer2 proxyToUse = getProxy(entry.getValue());
            return null != proxyToUse ? proxyToUse.newProxyHandler(sa) : null;
        }
        return null;
    }

    private ProxyServer2 getProxy(final String name) {
        return proxyServerProvider.getInstance(name);
    }

    public void addRouting(final DestinationPattern pattern, final String proxy) {
        registeredRoutingMap.put(pattern, proxy);
    }

    public void removeRouting(final DestinationPattern pattern) {
        registeredRoutingMap.remove(pattern);
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

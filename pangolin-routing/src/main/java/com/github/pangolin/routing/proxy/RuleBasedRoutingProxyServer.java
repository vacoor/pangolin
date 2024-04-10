package com.github.pangolin.routing.proxy;

import com.github.pangolin.routing.pattern.DestinationPattern;
import io.netty.channel.ChannelHandler;
import lombok.extern.slf4j.Slf4j;

import java.net.InetSocketAddress;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Slf4j
public class RuleBasedRoutingProxyServer implements ProxyServer {
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

    public RuleBasedRoutingProxyServer(final String name, final ProxyServerProvider provider, final Map<DestinationPattern, String> rules) {
        this.name = name;
        this.proxyServerProvider = provider;
        this.registeredRoutingMap.putAll(rules);
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
            log.info("{} -> {}", sa, entry.getValue());
            final ProxyServer proxyToUse = getProxy(entry.getValue());
            return null != proxyToUse ? proxyToUse.newProxyHandler(sa) : null;
        }
        return null;
    }

    private ProxyServer getProxy(final String name) {
        return proxyServerProvider.getInstance(name);
    }

    public void addRouting(final DestinationPattern pattern, final String proxy) {
        registeredRoutingMap.put(pattern, proxy);
    }

    public void removeRouting(final DestinationPattern pattern) {
        registeredRoutingMap.remove(pattern);
    }

}

package com.github.pangolin.routing.config;

import com.github.pangolin.routing.proxy.ProxyServer;
import com.github.pangolin.routing.proxy.ServerProvider;
import com.github.pangolin.routing.rule.RulesProvider;
import com.github.pangolin.routing.rule.pattern.DestinationPattern;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.netflix.loadbalancer.LoadBalancerStats;

import java.util.*;

public class ServerRegistry extends ServerFactory implements ServerProvider, RulesProvider, RouteletContext {
    private final RouteletContext parent;
    private final Map<String, ProxyServer> proxies = Maps.newLinkedHashMap();
    private final Map<DestinationPattern, String> rules = Maps.newLinkedHashMap();
    private final LoadBalancerStats stats;

    public ServerRegistry(final LoadBalancerStats stats) {
        this(null, stats);
    }

    public ServerRegistry(final RouteletContext parent, final LoadBalancerStats stats) {
        super(stats);
        this.parent = parent;
        this.stats = stats;
    }

    public void register(final String name, final String serverUrl) {
        proxies.put(name, create(name, serverUrl));
    }

    public void register(final String name, final String type, final List<String> servers) {
        LazyServerProvider lazyServerProvider = new LazyServerProvider(servers, this);
        ProxyServer g = createServerGroup(name, type, lazyServerProvider);
        proxies.put(name, g);
    }

    @Override
    public List<String> names() {
        return getServerNames();
    }

    public List<String> getServerNames() {
        return Lists.newArrayList(proxies.keySet());
    }

    @Override
    public ProxyServer getServer(final String name) {
        ProxyServer proxyServer = proxies.get(name);
        if (null != proxyServer) {
            return proxyServer;
        }
        return null != parent ? parent.getServer(name) : null;
    }

    @Override
    public List<ProxyServer> getServers() {
        final List<ProxyServer> a = Lists.newLinkedList();
        if (null != parent) {
            a.addAll(parent.getServers());
        }
        a.addAll(proxies.values());
        return a;
    }

    public void register(final DestinationPattern pattern, final String server) {
        rules.put(pattern, server);
    }

    @Override
    public Map<DestinationPattern, String> getRules() {
        Map<DestinationPattern, String> x = Maps.newLinkedHashMap();
        if (null != parent) {
            x.putAll(parent.getRules());
        }
        x.putAll(rules);
        return x;
    }
}
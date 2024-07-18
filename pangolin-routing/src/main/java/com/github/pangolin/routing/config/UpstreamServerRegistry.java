package com.github.pangolin.routing.config;

import com.github.pangolin.routing.route.predicate.RoutePredicate;
import com.github.pangolin.routing.upstream.UpstreamServer;
import com.github.pangolin.routing.upstream.UpstreamServerProvider;
import com.github.pangolin.routing.route.RouteProvider;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.netflix.loadbalancer.LoadBalancerStats;

import java.util.*;

public class UpstreamServerRegistry extends ServerFactory implements UpstreamServerProvider, RouteProvider, RouteletContext {
    private final RouteletContext parent;
    private final Map<String, UpstreamServer> proxies = Maps.newLinkedHashMap();
    private final Map<RoutePredicate, String> rules = Maps.newLinkedHashMap();
    private final LoadBalancerStats stats;

    public UpstreamServerRegistry(final LoadBalancerStats stats) {
        this(null, stats);
    }

    public UpstreamServerRegistry(final RouteletContext parent, final LoadBalancerStats stats) {
        super(stats);
        this.parent = parent;
        this.stats = stats;
    }

    public void register(final String name, final String serverUrl) {
        proxies.put(name, create(name, serverUrl));
    }

    public void register(final String name, final String type, final List<String> servers) {
        LazyUpstreamServerProvider lazyServerProvider = new LazyUpstreamServerProvider(servers, this);
        UpstreamServer g = createServerGroup(name, type, lazyServerProvider);
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
    public UpstreamServer getServer(final String name) {
        UpstreamServer upstreamServer = proxies.get(name);
        if (null != upstreamServer) {
            return upstreamServer;
        }
        return null != parent ? parent.getServer(name) : null;
    }

    @Override
    public List<UpstreamServer> getServers() {
        final List<UpstreamServer> a = Lists.newLinkedList();
        a.addAll(proxies.values());
        if (null != parent) {
            a.addAll(parent.getServers());
        }
        return a;
    }

    public void register(final RoutePredicate pattern, final String server) {
        rules.put(pattern, server);
    }

    @Override
    public Map<RoutePredicate, String> getRoutes() {
        Map<RoutePredicate, String> x = Maps.newLinkedHashMap();
        x.putAll(rules);
        if (null != parent) {
            x.putAll(parent.getRoutes());
        }
        return x;
    }
}
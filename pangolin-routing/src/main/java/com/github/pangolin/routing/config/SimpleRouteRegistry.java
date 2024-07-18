package com.github.pangolin.routing.config;

import com.github.pangolin.routing.route.RouteRegistry;
import com.github.pangolin.routing.route.predicate.RoutePredicate;
import com.github.pangolin.routing.upstream.UpstreamServer;
import com.github.pangolin.routing.upstream.UpstreamServerRegistry;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.netflix.loadbalancer.LoadBalancerStats;

import java.util.*;

public class SimpleRouteRegistry extends ServerFactory implements UpstreamServerRegistry, RouteRegistry, RouteContext {
    private final RouteContext parent;
    private final Map<String, UpstreamServer> upstreams = Maps.newLinkedHashMap();
    private final Map<RoutePredicate, String> routes = Maps.newLinkedHashMap();
    private final LoadBalancerStats stats;

    public SimpleRouteRegistry(final LoadBalancerStats stats) {
        this(null, stats);
    }

    public SimpleRouteRegistry(final RouteContext parent, final LoadBalancerStats stats) {
        super(stats);
        this.parent = parent;
        this.stats = stats;
    }

    public void register(final String name, final String serverUrl) {
        upstreams.put(name, create(name, serverUrl));
    }

    public void register(final String name, final String type, final List<String> servers) {
        LazyUpstreamServerRegistry lazyServerProvider = new LazyUpstreamServerRegistry(servers, this);
        UpstreamServer g = createServerGroup(name, type, lazyServerProvider);
        upstreams.put(name, g);
    }

    @Override
    public List<String> names() {
        return getServerNames();
    }

    public List<String> getServerNames() {
        return Lists.newArrayList(upstreams.keySet());
    }

    @Override
    public UpstreamServer getServer(final String name) {
        UpstreamServer upstreamServer = upstreams.get(name);
        if (null != upstreamServer) {
            return upstreamServer;
        }
        return null != parent ? parent.getServer(name) : null;
    }

    @Override
    public List<UpstreamServer> getServers() {
        final List<UpstreamServer> a = Lists.newLinkedList();
        a.addAll(upstreams.values());
        if (null != parent) {
            a.addAll(parent.getServers());
        }
        return a;
    }

    public void register(final RoutePredicate pattern, final String server) {
        routes.put(pattern, server);
    }

    @Override
    public Map<RoutePredicate, String> getRoutes() {
        Map<RoutePredicate, String> x = Maps.newLinkedHashMap();
        x.putAll(routes);
        if (null != parent) {
            x.putAll(parent.getRoutes());
        }
        return x;
    }
}
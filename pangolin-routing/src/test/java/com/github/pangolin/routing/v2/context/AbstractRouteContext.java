package com.github.pangolin.routing.v2.context;

import com.github.pangolin.routing.v2.route.Route;
import com.github.pangolin.routing.v2.route.RouteRegistry;
import com.github.pangolin.routing.v2.route.predicate.RoutePredicate;
import com.github.pangolin.routing.v2.upstream.UpstreamRegistry;
import com.github.pangolin.routing.v2.upstream.Upstream;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;

import java.net.InetSocketAddress;
import java.util.List;
import java.util.Map;

public abstract class AbstractRouteContext implements RouteContext, RouteRegistry<InetSocketAddress>, UpstreamRegistry {
    private final RouteContext parent;
    private final List<Route<InetSocketAddress>> routes = Lists.newLinkedList();
    private final Map<String, Upstream> upstreams = Maps.newLinkedHashMap();

    public AbstractRouteContext(final RouteContext parent) {
        this.parent = parent;
    }

    @Override
    public void addRoute(final Route<InetSocketAddress> route) {
        routes.add(route);
    }

    public Route getRoute(final InetSocketAddress destination) {
        for (final Route<InetSocketAddress> route : routes) {
            final Iterable<RoutePredicate<InetSocketAddress>> predicates = route.getPredicates();
            for (final RoutePredicate<InetSocketAddress> predicate : predicates) {
                if (predicate.test(destination)) {
                    return route;
                }
            }
        }
        return null != parent ? parent.getRoute(destination) : null;
    }

    @Override
    public void addUpstream(final String name, final Upstream upstream) {
        upstreams.put(name, upstream);
    }

    @Override
    public Upstream getUpstream(final String name) {
        final Upstream upstream = upstreams.get(name);
        if (null != upstream || null == parent) {
            return upstream;
        }
        return parent.getUpstream(name);
    }

    @Override
    public Upstream choose(final InetSocketAddress destination) {
        final Route route = getRoute(destination);
        return null != route ? getUpstream(route.getUpstream()) : null;
    }

}
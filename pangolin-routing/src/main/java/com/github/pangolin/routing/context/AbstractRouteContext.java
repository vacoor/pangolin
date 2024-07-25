package com.github.pangolin.routing.context;

import com.github.pangolin.routing.route.RouteRegistry;
import com.github.pangolin.routing.upstream.UpstreamRegistry;
import com.github.pangolin.routing.route.Route;
import com.github.pangolin.routing.route.predicate.RoutePredicate;
import com.github.pangolin.routing.server.Acceptor;
import com.github.pangolin.routing.server.AcceptorProvider;
import com.github.pangolin.routing.upstream.Upstream;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;

import java.net.InetSocketAddress;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

public abstract class AbstractRouteContext implements RouteContext, RouteRegistry<InetSocketAddress>, UpstreamRegistry, AcceptorProvider {
    private final RouteContext parent;
    private final List<Route<InetSocketAddress>> routes = Lists.newLinkedList();
    private final Map<String, Upstream> upstreams = Maps.newLinkedHashMap();

    private final List<Acceptor> acceptors = Lists.newLinkedList();

    public AbstractRouteContext(final RouteContext parent) {
        this.parent = parent;
    }

    @Override
    public RouteContext parent() {
        return parent;
    }

    @Override
    public Iterable<Route> routes() {
        return null == parent ? Collections.unmodifiableList(routes) : Iterables.concat(routes, parent.routes());
    }

    @Override
    public void addRoute(final Route<InetSocketAddress> route) {
        routes.add(route);
    }

    @Override
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

    public void addAcceptors(final Acceptor... acceptors) {
        this.acceptors.addAll(Arrays.asList(acceptors));
    }

    @Override
    public List<Acceptor> getAcceptors() {
        return acceptors;
    }
}
package com.github.pangolin.routing.v2.context;

import com.github.pangolin.routing.v2.route.Route;
import com.github.pangolin.routing.v2.route.RouteChooser;
import com.github.pangolin.routing.v2.route.RouteRegistry;
import com.github.pangolin.routing.v2.upstream.UpstreamRegistry;
import com.github.pangolin.routing.v2.upstream.UpstreamServer;
import com.google.common.collect.Lists;

import java.net.InetSocketAddress;
import java.util.List;

public class SimpleRouteContext implements UpstreamRegistry, RouteRegistry, RouteChooser {
    private final List<Route> routes = Lists.newLinkedList();

    @Override
    public void addRoute(final Route route) {

    }

    @Override
    public void addUpstream(final String name, final UpstreamServer upstream) {

    }

    public UpstreamServer getUpstream(final String name) {
        return null;
    }

    @Override
    public UpstreamServer choose(final InetSocketAddress destination) {
        for (final Route route : routes) {
            if (route.getPredicate().test(destination)) {
                return getUpstream(route.getUpstream());
            }
        }
        return null;
    }

}
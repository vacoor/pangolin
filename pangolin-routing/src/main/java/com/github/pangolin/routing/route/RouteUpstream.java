package com.github.pangolin.routing.route;

import com.github.pangolin.routing.upstream.DynamicUpstream;
import com.github.pangolin.routing.upstream.Upstream;
import com.github.pangolin.routing.upstream.UpstreamRegistry;

import java.net.InetSocketAddress;

/**
 *
 */
public class RouteUpstream extends DynamicUpstream {
    public static final String NAME = "DEFAULT";

    private final UpstreamRegistry upstreams;
    private final RouteRegistry<InetSocketAddress> routes;

    public RouteUpstream(final RouteRegistry<InetSocketAddress> routes, final UpstreamRegistry upstreams) {
        super(NAME);
        this.routes = routes;
        this.upstreams = upstreams;
    }

    @Override
    protected Upstream choose(final InetSocketAddress destination) {
        final Route route = getRoute(destination);
        return null != route ? getUpstream(route.getUpstream()) : null;
    }

    private Route getRoute(final InetSocketAddress destination) {
        return routes.getRoute(destination);
    }

    private Upstream getUpstream(final String upstream) {
        return upstreams.getUpstream(upstream);
    }

}

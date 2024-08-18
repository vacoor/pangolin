package com.github.pangolin.routing.context;

import com.github.pangolin.routing.route.Route;
import com.github.pangolin.routing.upstream.Upstream;

import java.net.InetSocketAddress;

public interface RouteContext {

    RouteContext parent();

    Iterable<Route> routes();

    Route getRoute(final InetSocketAddress destination);

    Upstream getUpstream(final String name);

    Upstream choose(final InetSocketAddress destination);

    <T> T attr(final String key);

    void attr(final String key, final Object value);

}

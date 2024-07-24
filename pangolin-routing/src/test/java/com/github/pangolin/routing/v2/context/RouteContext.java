package com.github.pangolin.routing.v2.context;

import com.github.pangolin.routing.v2.route.Route;
import com.github.pangolin.routing.v2.route.RouteRegistry;
import com.github.pangolin.routing.v2.upstream.UpstreamRegistry;
import com.github.pangolin.routing.v2.upstream.Upstream;

import java.net.InetSocketAddress;

public interface RouteContext {

    RouteContext parent();

    Route getRoute(final InetSocketAddress destination);

    Upstream getUpstream(final String name);

    Upstream choose(final InetSocketAddress destination);

}

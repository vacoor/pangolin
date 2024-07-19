package com.github.pangolin.routing.v2.context;

import com.github.pangolin.routing.v2.route.Route;
import com.github.pangolin.routing.v2.route.RouteRegistry;
import com.github.pangolin.routing.v2.upstream.UpstreamRegistry;
import com.github.pangolin.routing.v2.upstream.UpstreamServer;

import java.net.InetSocketAddress;

public interface RouteContext extends UpstreamRegistry, RouteRegistry<InetSocketAddress> {

    UpstreamServer getUpstream(final String name);

    Route getRoute(final InetSocketAddress destination);

    UpstreamServer choose(final InetSocketAddress destination);

}

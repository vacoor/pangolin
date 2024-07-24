package com.github.pangolin.routing.v2.route;

import com.github.pangolin.routing.v2.upstream.Upstream;

import java.net.InetSocketAddress;

public interface RouteChooser {

    Upstream choose(final InetSocketAddress destination);

}

package com.github.pangolin.routing.v2.route;

import com.github.pangolin.routing.v2.upstream.UpstreamServer;

import java.net.InetSocketAddress;

public interface RouteChooser {

    UpstreamServer choose(final InetSocketAddress destination);

}

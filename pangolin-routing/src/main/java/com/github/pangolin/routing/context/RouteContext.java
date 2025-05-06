package com.github.pangolin.routing.context;

import com.github.pangolin.routing.support.DatagramChannelFactory;
import com.github.pangolin.routing.support.SocketChannelFactory;
import com.github.pangolin.routing.route.Route;
import com.github.pangolin.routing.upstream.Upstream;

import java.net.InetSocketAddress;
import java.util.List;

public interface RouteContext {

    RouteContext parent();

    Iterable<Route> routes();

    Route getRoute(final InetSocketAddress destination);

    List<Upstream> upstreams();

    Upstream getUpstream(final String name);

    <T> T attr(final String key);

    void attr(final String key, final Object value);

    SocketChannelFactory newSocketChannelFactory();

    SocketChannelFactory newSocketChannelFactory(final String upstream);

    DatagramChannelFactory newDatagramChannelFactory();

    DatagramChannelFactory newDatagramChannelFactory(final String upstream);

}

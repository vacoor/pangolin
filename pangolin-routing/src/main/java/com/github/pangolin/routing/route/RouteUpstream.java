package com.github.pangolin.routing.route;

import com.github.pangolin.routing.upstream.AbstractUpstream;
import com.github.pangolin.routing.upstream.Upstream;
import com.github.pangolin.routing.upstream.UpstreamRegistry;
import io.netty.channel.ChannelHandler;

import java.net.InetSocketAddress;
import java.net.SocketAddress;

/**
 *
 */
public class RouteUpstream extends AbstractUpstream {
    private final UpstreamRegistry upstreams;
    private final RouteRegistry<InetSocketAddress> routes;

    public RouteUpstream(final RouteRegistry<InetSocketAddress> routes, final UpstreamRegistry upstreams) {
        super("ROUTE");
        this.routes = routes;
        this.upstreams = upstreams;
    }

    @Override
    public SocketAddress address() {
        return null;
    }

    @Override
    public boolean isVirtual() {
        return true;
    }

    @Override
    public ChannelHandler newSocketProxyHandler(final InetSocketAddress destination) {
        final Upstream upstream = choose(destination);
        return null != upstream ? upstream.newSocketProxyHandler(destination) : null;
    }

    @Override
    public ChannelHandler newDatagramProxyHandler(final InetSocketAddress destination) {
        final Upstream upstream = choose(destination);
        return null != upstream ? upstream.newDatagramProxyHandler(destination) : null;
    }

    private Upstream choose(final InetSocketAddress destination) {
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

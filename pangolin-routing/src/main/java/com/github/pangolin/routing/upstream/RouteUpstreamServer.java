package com.github.pangolin.routing.upstream;

import com.github.pangolin.routing.route.RouteRegistry;
import com.github.pangolin.routing.route.predicate.RoutePredicate;
import io.netty.channel.ChannelHandler;
import lombok.extern.slf4j.Slf4j;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.Map;

/**
 * @since 20240411
 */
@Slf4j
public class RouteUpstreamServer extends AbstractUpstreamServer {
    private final RouteRegistry routes;
    private final UpstreamServerRegistry upstreams;

    public RouteUpstreamServer(final String name, final RouteRegistry routes, final UpstreamServerRegistry upstreams) {
        super(name);
        this.routes = routes;
        this.upstreams = upstreams;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public ChannelHandler newSocketProxyHandler(final InetSocketAddress destination) {
        final UpstreamServer upstream = lookup(destination);
        return null != upstream ? upstream.newSocketProxyHandler(destination) : null;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public ChannelHandler newDatagramProxyHandler(final InetSocketAddress destination) {
        final UpstreamServer upstream = lookup(destination);
        return null != upstream ? upstream.newDatagramProxyHandler(destination) : null;
    }

    private UpstreamServer lookup(final SocketAddress destination) {
        if (!(destination instanceof InetSocketAddress)) {
            log.info("[ROUTE] will bypass the upstream => {}", destination);
            return null;
        }

        final InetSocketAddress address = (InetSocketAddress) destination;
        final Map<RoutePredicate, String> rules = routes.getRoutes();
        for (final Map.Entry<RoutePredicate, String> route : rules.entrySet()) {
            if (route.getKey().test(address)) {
                final UpstreamServer upstream = upstreams.getServer(route.getValue());
                if (null == upstream) {
                    log.warn("[ROUTING] NOT FOUND the upstream '{}' => {}:{}", route.getValue(), address.getHostString(), address.getPort());
                } else {
                    log.info("[ROUTE] will use the upstream '{}' => {}:{}", route.getValue(), address.getHostString(), address.getPort());
                    return upstream;
                }
            }
        }

        log.info("[ROUTING] will bypass the upstream => {}:{}", address.getHostString(), address.getPort());
        return null;
    }

}

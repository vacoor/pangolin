package com.github.pangolin.routing.context;

import com.github.pangolin.routing.handler.internal.server.support.DatagramChannelFactory;
import com.github.pangolin.routing.handler.internal.server.support.SocketChannelFactory;
import com.github.pangolin.routing.handler.internal.server.support.StandardDatagramChannelFactory;
import com.github.pangolin.routing.handler.internal.server.support.StandardSocketChannelFactory;
import com.github.pangolin.routing.route.Route;
import com.github.pangolin.routing.route.RouteRegistry;
import com.github.pangolin.routing.route.predicate.RoutePredicate;
import com.github.pangolin.routing.server.Acceptor;
import com.github.pangolin.routing.server.AcceptorProvider;
import com.github.pangolin.routing.support.ProxyDatagramChannelFactory;
import com.github.pangolin.routing.support.ProxySocketChannelFactory;
import com.github.pangolin.routing.support.SimpleAliasRegistry;
import com.github.pangolin.routing.upstream.AbstractUpstream;
import com.github.pangolin.routing.upstream.Upstream;
import com.github.pangolin.routing.upstream.UpstreamRegistry;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import io.netty.channel.ChannelHandler;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public abstract class AbstractRouteContext extends SimpleAliasRegistry implements RouteContext, RouteRegistry<InetSocketAddress>, UpstreamRegistry, AcceptorProvider {
    private final RouteContext parent;
    private final List<Route<InetSocketAddress>> routes = Lists.newLinkedList();
    private final Map<String, Upstream> upstreams = Maps.newLinkedHashMap();
    private final Map<String, Object> attributes = Maps.newLinkedHashMap();

    private final List<Acceptor> acceptors = Lists.newLinkedList();
    private final Upstream self = new ContextUpstream("Ctx-" + UUID.randomUUID().toString(), this);

    public AbstractRouteContext(final RouteContext parent) {
        this.parent = parent;
    }

    @Override
    public RouteContext parent() {
        return parent;
    }

    @Override
    public Iterable<Route> routes() {
        return null == parent ? Collections.unmodifiableList(routes) : Iterables.concat(routes, parent.routes());
    }

    @Override
    public void addRoute(final Route<InetSocketAddress> route) {
        routes.add(route);
    }

    @Override
    public Route getRoute(final InetSocketAddress destination) {
        // TODO get from cache.
        for (final Route<InetSocketAddress> route : routes) {
            final Iterable<RoutePredicate<InetSocketAddress>> predicates = route.getPredicates();
            for (final RoutePredicate<InetSocketAddress> predicate : predicates) {
                if (predicate.test(destination)) {
                    // TODO put to cache
                    return route;
                }
            }
        }
        return null != parent ? parent.getRoute(destination) : null;
    }

    @Override
    public void addUpstream(final String name, final Upstream upstream) {
        upstreams.put(name, upstream);
    }

    @Override
    public List<Upstream> upstreams() {
        final List<Upstream> upstreams = Lists.newLinkedList();
        if (null != parent) {
            upstreams.addAll(parent.upstreams());
        }
        upstreams.addAll(this.upstreams.values());
        return Collections.unmodifiableList(upstreams);
    }

    @Override
    public Upstream getUpstream(final String name) {
        final String nameToUse = canonicalName(name);
        final Upstream upstream = upstreams.get(nameToUse);
        if (null != upstream || null == parent) {
            return upstream;
        }
        return parent.getUpstream(name);
    }

    @Override
    public Upstream choose(final InetSocketAddress destination) {
        final Route route = getRoute(destination);
        return null != route ? getUpstream(route.getUpstream()) : null;
    }

    public void addAcceptors(final Acceptor... acceptors) {
        this.acceptors.addAll(Arrays.asList(acceptors));
    }

    @Override
    public List<Acceptor> getAcceptors() {
        return acceptors;
    }

    @Override
    public <T> T attr(final String key) {
        if (attributes.containsKey(key)) {
            return (T) attributes.get(key);
        }
        return null != parent ? parent.attr(key) : null;
    }

    @Override
    public void attr(final String key, final Object value) {
        attributes.put(key, value);
    }

    @Override
    public SocketChannelFactory newSocketChannelFactory() {
        return newSocketChannelFactory(self);
    }

    @Override
    public SocketChannelFactory newSocketChannelFactory(final String upstream) {
        return newSocketChannelFactory(getUpstream(upstream));
    }

    @Override
    public DatagramChannelFactory newDatagramChannelFactory() {
        return newDatagramChannelFactory(self);
    }

    @Override
    public DatagramChannelFactory newDatagramChannelFactory(final String upstream) {
        return newDatagramChannelFactory(getUpstream(upstream));
    }

    protected SocketChannelFactory newSocketChannelFactory(final Upstream upstream) {
        return null != upstream ? new ProxySocketChannelFactory(upstream, bypass(), null) : new StandardSocketChannelFactory(null);
    }

    protected DatagramChannelFactory newDatagramChannelFactory(final Upstream upstream) {
        return null != upstream ? new ProxyDatagramChannelFactory(upstream, bypass()) : new StandardDatagramChannelFactory();
    }

    private List<String> bypass() {
        return Arrays.asList("::1", "127.0.0.1", "localhost");
    }

    protected class ContextUpstream extends AbstractUpstream {
        private final RouteContext context;

        public ContextUpstream(final String name, final RouteContext context) {
            super(name);
            this.context = context;
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
            final Upstream upstream = context.choose(destination);
            return null != upstream ? upstream.newSocketProxyHandler(destination) : null;
        }

        @Override
        public ChannelHandler[] newSocketProxyHandlers(final InetSocketAddress destination) {
            final Upstream upstream = context.choose(destination);
            return null != upstream ? upstream.newSocketProxyHandlers(destination) : new ChannelHandler[0];
        }

        @Override
        public ChannelHandler newDatagramProxyHandler(final InetSocketAddress destination) {
            final Upstream upstream = context.choose(destination);
            return null != upstream ? upstream.newDatagramProxyHandler(destination) : null;
        }
    }
}
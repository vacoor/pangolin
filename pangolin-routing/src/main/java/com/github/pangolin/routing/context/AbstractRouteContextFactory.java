package com.github.pangolin.routing.context;

import com.github.pangolin.routing.route.Route;
import com.github.pangolin.routing.route.predicate.RoutePredicate;
import com.github.pangolin.routing.route.predicate.RoutePredicateFactory;
import com.github.pangolin.routing.route.predicate.UnknownRoutePredicate;
import com.github.pangolin.routing.upstream.Upstream;
import com.github.pangolin.routing.upstream.UpstreamCombiner;
import com.github.pangolin.routing.upstream.UpstreamFactory;
import com.github.pangolin.routing.upstream.UpstreamRegistry;
import com.google.common.collect.Maps;
import lombok.extern.slf4j.Slf4j;

import java.net.InetSocketAddress;
import java.net.URL;
import java.util.Collections;
import java.util.Map;

@Slf4j
public abstract class AbstractRouteContextFactory implements RouteContextFactory,
        UpstreamFactoriesAware, UpstreamCombinersAware, RoutePredicateFactoriesAware {


    protected Iterable<UpstreamFactory> upstreamFactories = Collections.emptyList();
    protected Map<String, UpstreamCombiner> upstreamCombiners = Maps.newLinkedHashMap();
    protected Map<String, RoutePredicateFactory> predicateFactories = Maps.newLinkedHashMap();

    @Override
    public void setUpstreamFactories(final Iterable<UpstreamFactory> factories) {
        this.upstreamFactories = factories;
    }

    @Override
    public void setUpstreamCombiners(final Map<String, UpstreamCombiner> combiners) {
        this.upstreamCombiners = combiners;
    }


    @Override
    public void setRoutePredicateFactories(final Map<String, RoutePredicateFactory> factories) {
        this.predicateFactories = factories;
    }

    protected Upstream apply(final String name, final String url) {
        for (final UpstreamFactory factory : upstreamFactories) {
            if (factory.accept(url)) {
                return factory.apply(name, url);
            }
        }
        throw new IllegalArgumentException("Unable to find UpstreamFactory with url " + name);
    }

    protected Upstream apply(final String name, final String type, final Iterable<String> names, final UpstreamRegistry registry) {
        final UpstreamCombiner combiner = upstreamCombiners.get(type);
        if (null == combiner) {
            throw new IllegalArgumentException("Unable to find UpstreamCombiner with name " + type);
        }
        return combiner.combine(name, names, registry);
    }

    protected Route<InetSocketAddress> apply(final String definition, final URL location, final Map<String, String> nameMapping) {
        final String[] segments = definition.split(",");
        final Iterable<RoutePredicate<InetSocketAddress>> predicates = apply(segments[0], segments[1], location);
        if (segments.length > 2) {
            String nameToUse = nameMapping.get(segments[2]);
            nameToUse = null != nameToUse ? nameToUse : segments[2];
            return Route.of(predicates, nameToUse);
        }
        return null;
    }

    protected Iterable<RoutePredicate<InetSocketAddress>> apply(final String name, final String predicate, final URL location) {
        final RoutePredicateFactory factory = predicateFactories.get(name);
        if (factory == null) {
            // throw new IllegalArgumentException( "Unable to find RoutePredicateFactory with name " + name);
            log.warn("Unable to find RoutePredicateFactory with name " + name);
            return Collections.singleton(UnknownRoutePredicate.<InetSocketAddress>of(String.format("%s,%s", name, predicate)));
        }
        return factory.apply(predicate, location);
    }

}
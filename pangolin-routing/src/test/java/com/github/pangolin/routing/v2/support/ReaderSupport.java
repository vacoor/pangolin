package com.github.pangolin.routing.v2.support;

import com.github.pangolin.routing.v2.route.Route;
import com.github.pangolin.routing.v2.route.predicate.RoutePredicate;
import com.github.pangolin.routing.v2.route.predicate.RoutePredicateFactory;
import com.github.pangolin.routing.v2.route.predicate.RoutePredicateSetFactory;
import com.github.pangolin.routing.v2.route.predicate.UnknownRoutePredicate;
import com.github.pangolin.routing.v2.upstream.UpstreamCombiner;
import com.github.pangolin.routing.v2.upstream.UpstreamFactory;
import com.github.pangolin.routing.v2.upstream.UpstreamRegistry;
import com.github.pangolin.routing.v2.upstream.Upstream;
import com.google.common.collect.Maps;
import com.netflix.loadbalancer.LoadBalancerStats;
import lombok.extern.slf4j.Slf4j;

import java.net.InetSocketAddress;
import java.net.URL;
import java.util.Collections;
import java.util.Map;

@Slf4j
public class ReaderSupport {
    protected final LoadBalancerStats stats;
    protected final Iterable<UpstreamFactory> factories;
    protected final Map<String, UpstreamCombiner> combiners = Maps.newLinkedHashMap();
    protected final Map<String, RoutePredicateFactory<InetSocketAddress, String>> predicates = Maps.newLinkedHashMap();

    public ReaderSupport(final LoadBalancerStats stats,
                         final Iterable<UpstreamFactory> factories,
                         final Iterable<UpstreamCombiner> combiners,
                         final Iterable<RoutePredicateFactory<InetSocketAddress, String>> predicates) {
        this.stats = stats;
        this.factories = factories;
        this.initUpstreamCombiners(combiners);
        this.initPredicateFactories(predicates);
    }

    private void initUpstreamCombiners(final Iterable<UpstreamCombiner> factories) {
        for (final UpstreamCombiner factory : factories) {
            final String key = factory.name();
            if (combiners.containsKey(key)) {
                System.err.println("A UpstreamCombiner named " + key
                        + " already exists, class: " + combiners.get(key)
                        + ". It will be overwritten.");
            }
            combiners.put(key, factory);
            System.out.println("Loaded UpstreamCombiner [" + key + "]");
        }
    }

    private void initPredicateFactories(final Iterable<RoutePredicateFactory<InetSocketAddress, String>> factories) {
        for (final RoutePredicateFactory factory : factories) {
            final String key = factory.name();
            if (predicates.containsKey(key)) {
                System.err.println("A RoutePredicateFactory named " + key
                        + " already exists, class: " + predicates.get(key)
                        + ". It will be overwritten.");
            }
            predicates.put(key, factory);
            System.out.println("Loaded RoutePredicateFactory [" + key + "]");
        }
        predicates.put("RULE-SET", new RoutePredicateSetFactory("RULE-SET", factories));
    }

    protected Upstream apply(final String name, final String url) {
        for (final UpstreamFactory factory : factories) {
            if (factory.accept(url)) {
                return factory.apply(name, url);
            }
        }
        throw new IllegalArgumentException("Unable to find UpstreamFactory with url " + name);
    }

    protected Upstream apply(final String name, final String type, final Iterable<String> names, final UpstreamRegistry registry) {
        final UpstreamCombiner combiner = combiners.get(type);
        if (null == combiner) {
            throw new IllegalArgumentException("Unable to find UpstreamCombiner with name " + type);
        }
        return combiner.combine(name, names, registry);
    }

    protected Route<InetSocketAddress> apply(final String definition, final URL location) {
        final String[] segments = definition.split(",");
        final Iterable<RoutePredicate<InetSocketAddress>> predicates = apply(segments[0], segments[1], location);
        return segments.length > 2 ? Route.of(predicates, segments[2]) : null;
    }

    protected Iterable<RoutePredicate<InetSocketAddress>> apply(final String name, final String predicate, final URL location) {
        final RoutePredicateFactory factory = predicates.get(name);
        if (factory == null) {
            // throw new IllegalArgumentException( "Unable to find RoutePredicateFactory with name " + name);
            log.error("Unable to find RoutePredicateFactory with name " + name);
            return Collections.singleton(UnknownRoutePredicate.<InetSocketAddress>of(String.format("%s,%s", name, predicate)));
        }
        return factory.apply(predicate, location);
    }

}
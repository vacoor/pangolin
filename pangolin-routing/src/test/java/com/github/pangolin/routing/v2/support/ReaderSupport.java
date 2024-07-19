package com.github.pangolin.routing.v2.support;

import com.github.pangolin.routing.config.ConfigurationException;
import com.github.pangolin.routing.config.clash.ClashConfiguration;
import com.github.pangolin.routing.v2.context.RouteContext;
import com.github.pangolin.routing.v2.context.SimpleRouteContext;
import com.github.pangolin.routing.v2.route.Route;
import com.github.pangolin.routing.v2.route.predicate.RoutePredicate;
import com.github.pangolin.routing.v2.route.predicate.RoutePredicateFactory;
import com.github.pangolin.routing.v2.route.predicate.RoutePredicateSetFactory;
import com.github.pangolin.routing.v2.route.predicate.UnknownRoutePredicate;
import com.github.pangolin.routing.v2.upstream.UpstreamRegistry;
import com.github.pangolin.routing.v2.upstream.UpstreamServer;
import com.github.pangolin.routing.v2.upstream.UpstreamServerCombiner;
import com.github.pangolin.routing.v2.upstream.UpstreamServerFactory;
import com.google.common.base.Preconditions;
import com.google.common.collect.Maps;
import com.netflix.loadbalancer.LoadBalancerStats;
import freework.net.Http;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Slf4j
public class ReaderSupport {
    protected final LoadBalancerStats stats;
    protected final Iterable<UpstreamServerFactory> factories;
    protected final Map<String, UpstreamServerCombiner> combiners = Maps.newLinkedHashMap();
    protected final Map<String, RoutePredicateFactory<InetSocketAddress, String>> predicates = Maps.newLinkedHashMap();

    public ReaderSupport(final LoadBalancerStats stats,
                         final Iterable<UpstreamServerFactory> factories,
                         final Iterable<UpstreamServerCombiner> combiners,
                         final Iterable<RoutePredicateFactory<InetSocketAddress, String>> predicates) {
        this.stats = stats;
        this.factories = factories;
        this.initUpstreamCombiners(combiners);
        this.initPredicateFactories(predicates);
    }

    private void initUpstreamCombiners(final Iterable<UpstreamServerCombiner> factories) {
        for (final UpstreamServerCombiner factory : factories) {
            final String key = factory.name();
            if (combiners.containsKey(key)) {
                System.err.println("A UpstreamServerCombiner named " + key
                        + " already exists, class: " + combiners.get(key)
                        + ". It will be overwritten.");
            }
            combiners.put(key, factory);
            System.out.println("Loaded UpstreamServerCombiner [" + key + "]");
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

    protected UpstreamServer apply(final String name, final String url) {
        for (final UpstreamServerFactory factory : factories) {
            if (factory.accept(url)) {
                return factory.apply(name, url);
            }
        }
        throw new IllegalArgumentException("Unable to find UpstreamServerFactory with url " + name);
    }

    protected UpstreamServer apply(final String name, final String type, final Iterable<String> names, final UpstreamRegistry registry) {
        final UpstreamServerCombiner combiner = combiners.get(type);
        if (null == combiner) {
            throw new IllegalArgumentException("Unable to find UpstreamServerCombiner with name " + type);
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
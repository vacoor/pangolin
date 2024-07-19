package com.github.pangolin.routing.v2.route.predicate;

import com.github.pangolin.routing.config.resolver.Utils;
import com.google.common.collect.Iterables;
import com.google.common.collect.Maps;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.MalformedURLException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Map;

public class RoutePredicateSetFactory<T, D> implements RoutePredicateFactory<InetSocketAddress, String> {
    private final String name;
    private final Iterable<RoutePredicateFactory<InetSocketAddress, String>> factories;

    public RoutePredicateSetFactory(final String name, final Iterable<RoutePredicateFactory<InetSocketAddress, String>> factories) {
        this.name = name;
        this.factories = factories;
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public Iterable<RoutePredicate<InetSocketAddress>> apply(final String definition, final URL location) {
        if (null == factories) {
            return null;
        }

        final URL locationToUse = resolve(location, definition);
        // FIXME.
        try {
            return Utils.lines(locationToUse, StandardCharsets.UTF_8)
                    .filter(StringUtils::hasText)
                    .map(line -> apply0(line, locationToUse))
                    .map(Iterables::concat)
                    .reduce(Iterables::concat)
                    .orElse(Collections.emptyList());
        } catch (final IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private URL resolve(final URL base, final String child) {
        try {
            return base.toURI().resolve(child).toURL();
        } catch (final URISyntaxException e) {
            throw new IllegalStateException(e);
        } catch (final MalformedURLException e) {
            throw new IllegalStateException(e);
        }
    }

    private Iterable<RoutePredicate<InetSocketAddress>> apply0(final String definition, final URL location) {
        final Map<String, RoutePredicateFactory<InetSocketAddress, String>> predicates = Maps.newHashMap();
        for (final RoutePredicateFactory<InetSocketAddress, String> factory : factories) {
            final String key = factory.name();
            if (predicates.containsKey(key)) {
                System.err.println("A RoutePredicateFactory named " + key
                        + " already exists, class: " + predicates.get(key)
                        + ". It will be overwritten.");
            }
            predicates.put(key, factory);
            System.out.println("Loaded RoutePredicateFactory [" + key + "]");
        }

        final int idx = definition.indexOf(",");
        if (idx <= 0) {
            throw new IllegalStateException("Unable to parse PredicateDefinition text '"
                    + definition + "'" + ", must be of the form name,definition");
        }
        final String name = definition.substring(0, idx);
        final String arg = definition.substring(idx + 1);

        final RoutePredicateFactory<InetSocketAddress, String> factory = predicates.get(name);
        if (factory == null) {
            throw new IllegalArgumentException("Unable to find RoutePredicateFactory with name " + name);
        }
        return factory.apply(arg, location);
    }

}

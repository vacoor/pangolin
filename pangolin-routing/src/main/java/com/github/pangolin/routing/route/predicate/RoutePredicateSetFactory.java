package com.github.pangolin.routing.route.predicate;

import com.github.pangolin.routing.util.Utils;
import com.google.common.collect.Iterables;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.MalformedURLException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Map;

@Slf4j
public class RoutePredicateSetFactory<T, D> implements RoutePredicateFactory<InetSocketAddress, String> {
    private final String name;
    private final Map<String, RoutePredicateFactory<InetSocketAddress, String>> predicates;

    public RoutePredicateSetFactory(final String name, final Map<String, RoutePredicateFactory<InetSocketAddress, String>> predicates) {
        this.name = name;
        this.predicates = predicates;
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public Iterable<RoutePredicate<InetSocketAddress>> apply(final String definition, final URL location) {
        if (null == predicates) {
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

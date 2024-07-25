package com.github.pangolin.routing.route;

import com.github.pangolin.routing.route.predicate.RoutePredicate;

public class Route<T> {
    private final Iterable<RoutePredicate<T>> predicates;
    private final String upstream;

    private Route(final Iterable<RoutePredicate<T>> predicates, final String upstream) {
        this.predicates = predicates;
        this.upstream = upstream;
    }

    public Iterable<RoutePredicate<T>> getPredicates() {
        return predicates;
    }

    public String getUpstream() {
        return upstream;
    }

    @Override
    public String toString() {
        return predicates + " -> " + upstream;
    }

    public static <T> Route<T> of(final Iterable<RoutePredicate<T>> predicates, final String upstream) {
        return new Route<>(predicates, upstream);
    }
}

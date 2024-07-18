package com.github.pangolin.routing.v2.route;

import com.github.pangolin.routing.v2.route.predicate.RoutePredicate;

public class Route<T> {
    private final RoutePredicate<T> predicate;
    private final String upstream;

    public Route(final RoutePredicate<T> predicate, final String upstream) {
        this.predicate = predicate;
        this.upstream = upstream;
    }

    public RoutePredicate<T> getPredicate() {
        return predicate;
    }

    public String getUpstream() {
        return upstream;
    }
}

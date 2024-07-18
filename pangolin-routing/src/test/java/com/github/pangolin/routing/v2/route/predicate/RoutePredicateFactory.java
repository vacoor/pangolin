package com.github.pangolin.routing.v2.route.predicate;

public interface RoutePredicateFactory<T, D> {

    String name();

    RoutePredicate<T> apply(final D definition);

}

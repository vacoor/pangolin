package com.github.pangolin.routing.route.predicate;

public interface RoutePredicateFactory<T, D> {

    String name();

    RoutePredicate<T> apply(final D definition);

}

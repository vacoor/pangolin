package com.github.pangolin.routing.route.predicate;

import java.net.URL;

public interface RoutePredicateFactory<T, D> {

    String name();

    Iterable<RoutePredicate<T>> apply(final D definition, final URL location);

}

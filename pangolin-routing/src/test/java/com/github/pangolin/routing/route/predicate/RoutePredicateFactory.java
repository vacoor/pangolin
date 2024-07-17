package com.github.pangolin.routing.route.predicate;

import java.util.function.Predicate;

public interface RoutePredicateFactory<T> {

    Predicate<T> apply(final RoutePredicateDefinition predicate);

}

package com.github.pangolin.routing.v2.route.predicate;

public interface RoutePredicate<T> {

    boolean test(final T t);

}

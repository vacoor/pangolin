package com.github.pangolin.routing.route.predicate;

public interface RoutePredicate<T> {

    boolean test(final T t);

}

package com.github.pangolin.routing.route;

public interface RouteRegistry<T> {

    Iterable<Route> routes();

    Route<T> getRoute(final T destination);

    void addRoute(final Route<T> route);

}

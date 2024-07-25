package com.github.pangolin.routing.route;

public interface RouteRegistry<T> {

    Iterable<Route> routes();

    void addRoute(final Route<T> route);

}

package com.github.pangolin.routing.route;

import com.github.pangolin.routing.route.predicate.RoutePredicate;

import java.util.Map;

public interface RouteRegistry {

    Map<RoutePredicate, String> getRoutes();

}
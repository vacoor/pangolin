package com.github.pangolin.routing.route;

import com.github.pangolin.routing.route.predicate.RoutePredicateFactory;

import java.util.Map;

public interface RoutePredicateFactoriesAware {

    void setRoutePredicateFactories(final Map<String, RoutePredicateFactory> factories);

}
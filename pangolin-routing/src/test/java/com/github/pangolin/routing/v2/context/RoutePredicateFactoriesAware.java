package com.github.pangolin.routing.v2.context;

import com.github.pangolin.routing.v2.route.predicate.RoutePredicateFactory;

import java.util.Map;

public interface RoutePredicateFactoriesAware {

    void setRoutePredicateFactories(final Map<String, RoutePredicateFactory> factories);

}
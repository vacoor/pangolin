package com.github.pangolin.routing.v2.route;

import java.util.List;

public class CachingRouteDefinitionSupplier implements RouteDefinitionSupplier {
    private final RouteDefinitionSupplier delegate;

    public CachingRouteDefinitionSupplier(final RouteDefinitionSupplier delegate) {
        this.delegate = delegate;
    }

    @Override
    public List<RouteDefinition> get() {
        return null;
    }

}

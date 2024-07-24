package com.github.pangolin.routing.v2.context;

/**
 *
 */
public class InMemoryRouteContext extends AbstractRouteContext {

    public InMemoryRouteContext() {
        this(null);
    }

    public InMemoryRouteContext(final RouteContext parent) {
        super(parent);
    }

}

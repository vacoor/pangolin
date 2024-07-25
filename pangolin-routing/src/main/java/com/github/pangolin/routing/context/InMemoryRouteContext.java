package com.github.pangolin.routing.context;

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

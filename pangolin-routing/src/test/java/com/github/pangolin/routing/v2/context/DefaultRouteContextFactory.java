package com.github.pangolin.routing.v2.context;

public class DefaultRouteContextFactory implements RouteContextFactory {

    @Override
    public RouteContext createContext(final RouteContext parent) {
        return new SimpleRouteContext(parent);
    }

}

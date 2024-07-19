package com.github.pangolin.routing.v2.context;

public interface RouteContextFactory {

    RouteContext createContext(final RouteContext parent);

}

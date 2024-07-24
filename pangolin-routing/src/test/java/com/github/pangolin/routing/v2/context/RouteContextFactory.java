package com.github.pangolin.routing.v2.context;

import java.net.URL;

public interface RouteContextFactory {

    RouteContext createContext(final URL url, final RouteContext parent) throws Exception;

}

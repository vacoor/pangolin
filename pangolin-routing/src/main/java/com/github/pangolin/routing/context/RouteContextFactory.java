package com.github.pangolin.routing.context;

import java.net.URL;

public interface RouteContextFactory {

    RouteContext create(final URL url, final RouteContext parent) throws Exception;

}

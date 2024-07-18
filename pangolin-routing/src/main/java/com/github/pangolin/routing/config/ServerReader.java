package com.github.pangolin.routing.config;

import java.io.IOException;
import java.net.URL;

public interface ServerReader {

    SimpleRouteRegistry load(final URL url, final RouteContext parent) throws IOException, ConfigurationException;

}

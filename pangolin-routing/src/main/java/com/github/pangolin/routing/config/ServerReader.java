package com.github.pangolin.routing.config;

import java.io.IOException;
import java.net.URL;

public interface ServerReader {

    UpstreamServerRegistry load(final URL url, final RouteletContext parent) throws IOException, ConfigurationException;

}

package com.github.pangolin.routing.context;

import com.github.pangolin.routing.config.ConfigurationException;

import java.io.IOException;
import java.net.URL;

public interface ServerReader {

    ServerRegistry load(final URL url, final RouteletContext parent) throws IOException, ConfigurationException;

}

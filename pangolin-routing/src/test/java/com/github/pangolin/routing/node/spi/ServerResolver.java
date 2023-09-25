package com.github.pangolin.routing.node.spi;

import com.github.pangolin.routing.node.Server;

import java.util.Properties;

/**
 */
public interface ServerResolver {

    boolean acceptsUrl(final String url);

    Server resolve(final String url, final Properties props);

}

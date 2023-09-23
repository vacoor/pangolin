package com.github.pangolin.routing.node.spi;

import java.util.Properties;

/**
 */
public interface ServerResolver {

    boolean acceptsUrl(final String url);

    ProxyServer resolve(final String url, final Properties props);

}

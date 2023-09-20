package com.github.pangolin.routing.internal.server;

import java.util.Properties;

/**
 */
public interface ServerResolver {

    boolean acceptsUrl(final String url);

    ProxyServer resolve(final String url, final Properties props);

}

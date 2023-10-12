package com.github.pangolin.routing.internal.node.spi;

import com.github.pangolin.routing.internal.node.ProxyServer;

import java.util.Properties;

/**
 */
public interface ServerResolver {

    boolean acceptsUrl(final String url);

    ProxyServer resolve(final String url, final Properties props);

}

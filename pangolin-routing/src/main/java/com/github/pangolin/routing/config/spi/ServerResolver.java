package com.github.pangolin.routing.config.spi;

import com.github.pangolin.routing.proxy.ProxyServer;

import java.util.Properties;

/**
 */
public interface ServerResolver {

    boolean acceptsUrl(final String url);

    ProxyServer resolve(final String url, final Properties props);

}

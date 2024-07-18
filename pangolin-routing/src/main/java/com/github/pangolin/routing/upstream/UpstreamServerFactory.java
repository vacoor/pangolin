package com.github.pangolin.routing.upstream;

import java.util.Properties;

/**
 *
 */
public interface UpstreamServerFactory {

    boolean accept(final String url);

    UpstreamServer create(final String url, final Properties props);

}

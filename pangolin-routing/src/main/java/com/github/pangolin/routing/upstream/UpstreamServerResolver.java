package com.github.pangolin.routing.upstream;

import java.util.Properties;

/**
 */
public interface UpstreamServerResolver {

    boolean acceptsUrl(final String url);

    UpstreamServer resolve(final String url, final Properties props);

}

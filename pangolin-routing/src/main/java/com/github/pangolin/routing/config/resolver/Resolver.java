package com.github.pangolin.routing.config.resolver;

import java.io.IOException;
import java.net.URL;
import java.util.List;

/**
 */
public interface Resolver<T> {
    boolean matches(String rule);

    List<T> resolve(String rule, URL baseUrl) throws IOException;
}

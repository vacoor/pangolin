package com.github.pangolin.routing.upstream;

import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;

public interface UpstreamCombiner {

    public abstract String name();

    public abstract Upstream combine(final String name, final Iterable<String> names, final UpstreamRegistry registry);

    /*
    public static UpstreamCombiner getInstance(final String name) {
        for (final UpstreamCombiner combiner : ServiceLoader.load(UpstreamCombiner.class)) {
            if (combiner.name().equals(name)) {
                return combiner;
            }
        }
        throw new ServiceConfigurationError(name);
    }
    */

}

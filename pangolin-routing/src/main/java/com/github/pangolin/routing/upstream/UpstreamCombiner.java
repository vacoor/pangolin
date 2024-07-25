package com.github.pangolin.routing.upstream;

public interface UpstreamCombiner {

    String name();

    Upstream combine(final String name, final Iterable<String> names, final UpstreamRegistry registry);

}

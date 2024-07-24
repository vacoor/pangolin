package com.github.pangolin.routing.v2.upstream;

public interface UpstreamCombiner {

    String name();

    Upstream combine(final String name, final Iterable<String> names, final UpstreamRegistry registry);

}

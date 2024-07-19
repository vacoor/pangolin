package com.github.pangolin.routing.v2.upstream;

public interface UpstreamServerCombiner {

    String name();

    UpstreamServer combine(final String name, final Iterable<String> names, final UpstreamRegistry registry);

}

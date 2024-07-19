package com.github.pangolin.routing.v2.upstream;

public interface UpstreamServerFactory {

    boolean accept(final String url);

    UpstreamServer apply(final String name, final String url);

}

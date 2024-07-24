package com.github.pangolin.routing.v2.upstream;

public interface UpstreamFactory {

    boolean accept(final String url);

    Upstream apply(final String name, final String url);

}

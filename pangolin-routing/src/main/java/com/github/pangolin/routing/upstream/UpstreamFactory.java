package com.github.pangolin.routing.upstream;

public interface UpstreamFactory {

    boolean accept(final String serverUrl);

    Upstream apply(final String name, final String serverUrl);

}

package com.github.pangolin.routing.upstream;

public interface UpstreamFactoriesAware {

    void setUpstreamFactories(final Iterable<UpstreamFactory> factories);

}
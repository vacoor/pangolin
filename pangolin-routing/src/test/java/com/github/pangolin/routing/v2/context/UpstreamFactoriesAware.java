package com.github.pangolin.routing.v2.context;

import com.github.pangolin.routing.v2.upstream.UpstreamFactory;

public interface UpstreamFactoriesAware {

    void setUpstreamFactories(final Iterable<UpstreamFactory> factories);

}
package com.github.pangolin.routing.context;

import com.github.pangolin.routing.upstream.UpstreamFactory;

public interface UpstreamFactoriesAware {

    void setUpstreamFactories(final Iterable<UpstreamFactory> factories);

}
package com.github.pangolin.routing.v2.context;

import com.github.pangolin.routing.v2.upstream.UpstreamCombiner;

import java.util.Map;

public interface UpstreamCombinersAware {

    void setUpstreamCombiners(final Map<String, UpstreamCombiner> combiners);

}
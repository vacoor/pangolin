package com.github.pangolin.routing.upstream;

import com.github.pangolin.routing.upstream.UpstreamCombiner;

import java.util.Map;

public interface UpstreamCombinersAware {

    void setUpstreamCombiners(final Map<String, UpstreamCombiner> combiners);

}
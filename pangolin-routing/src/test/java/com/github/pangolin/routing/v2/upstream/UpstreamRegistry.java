package com.github.pangolin.routing.v2.upstream;

public interface UpstreamRegistry {

    void addUpstream(final String name, final UpstreamServer upstream);

}
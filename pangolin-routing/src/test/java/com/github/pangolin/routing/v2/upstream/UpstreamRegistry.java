package com.github.pangolin.routing.v2.upstream;

public interface UpstreamRegistry {

    Upstream getUpstream(final String name);

    void addUpstream(final String name, final Upstream upstream);

}
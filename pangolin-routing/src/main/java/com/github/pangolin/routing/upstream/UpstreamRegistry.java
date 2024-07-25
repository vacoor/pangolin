package com.github.pangolin.routing.upstream;

public interface UpstreamRegistry {

    Upstream getUpstream(final String name);

    void addUpstream(final String name, final Upstream upstream);

}
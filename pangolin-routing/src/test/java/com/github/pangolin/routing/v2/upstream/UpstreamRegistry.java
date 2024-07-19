package com.github.pangolin.routing.v2.upstream;

public interface UpstreamRegistry {

    UpstreamServer getUpstream(final String name);

    void addUpstream(final String name, final UpstreamServer upstream);

}
package com.github.pangolin.routing.v2.stats;

import com.github.pangolin.routing.v2.upstream.Upstream;
import com.github.pangolin.routing.v2.upstream.UpstreamCombiner;
import com.github.pangolin.routing.v2.upstream.UpstreamRegistry;
import com.netflix.loadbalancer.LoadBalancerStats;

public class StatsUpstreamCombiner implements UpstreamCombiner {
    private final UpstreamCombiner delegate;
    private final LoadBalancerStats stats;

    public StatsUpstreamCombiner(final UpstreamCombiner delegate, final LoadBalancerStats stats) {
        this.delegate = delegate;
        this.stats = stats;
    }

    @Override
    public String name() {
        return delegate.name();
    }

    @Override
    public Upstream combine(final String name, final Iterable<String> names, final UpstreamRegistry registry) {
        final Upstream upstream = delegate.combine(name, names, registry);
        if (null != upstream && !(upstream instanceof StatsUpstream)) {
            return new StatsUpstream(upstream, stats);
        }
        return upstream;
    }
}
package com.github.pangolin.routing.v2.stats;

import com.github.pangolin.routing.v2.upstream.Upstream;
import com.github.pangolin.routing.v2.upstream.UpstreamFactory;
import com.netflix.loadbalancer.LoadBalancerStats;

public class StatsUpstreamFactory implements UpstreamFactory {
    private final UpstreamFactory delegate;
    private final LoadBalancerStats stats;

    public StatsUpstreamFactory(final UpstreamFactory delegate, final LoadBalancerStats stats) {
        this.delegate = delegate;
        this.stats = stats;
    }

    @Override
    public boolean accept(final String url) {
        return delegate.accept(url);
    }

    @Override
    public Upstream apply(final String name, final String url) {
        final Upstream upstream = delegate.apply(name, url);
        if (null != upstream && !(upstream instanceof StatsUpstream)) {
            return new StatsUpstream(upstream, stats);
        }
        return upstream;
    }

    @Override
    public int hashCode() {
        return delegate.hashCode();
    }

    @Override
    public String toString() {
        return delegate.toString();
    }
}
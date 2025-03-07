package com.github.pangolin.routing.upstream.stats;

import com.netflix.loadbalancer.LoadBalancerStats;

public interface StatsAware {
    void setStats(final LoadBalancerStats stats);
}
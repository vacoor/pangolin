package com.github.pangolin.routing.v2.stats;

import com.netflix.loadbalancer.LoadBalancerStats;

public interface StatsAware {
    void setStats(final LoadBalancerStats stats);
}
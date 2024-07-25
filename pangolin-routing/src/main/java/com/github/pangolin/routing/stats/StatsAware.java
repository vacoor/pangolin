package com.github.pangolin.routing.stats;

import com.netflix.loadbalancer.LoadBalancerStats;

public interface StatsAware {
    void setStats(final LoadBalancerStats stats);
}
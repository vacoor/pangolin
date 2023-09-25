package com.github.pangolin.routing.node;

import io.netty.util.concurrent.Promise;

/**
 *
 */
public interface HealthService extends ServiceInstance {
    interface Stats {

        double getAvgRt();

        long getMinRt();

        long getMaxRt();

    }

    Promise<Long> ping();

    boolean isPassingCheck();

    Stats getStats();

}

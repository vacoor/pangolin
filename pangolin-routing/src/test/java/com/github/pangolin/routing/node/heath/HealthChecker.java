package com.github.pangolin.routing.node.heath;

import com.github.pangolin.routing.node.ServerInstance;

public interface HealthChecker {

    boolean isHealthy(final ServerInstance instance);

}

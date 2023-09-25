package com.github.pangolin.routing.node.heath;

import com.github.pangolin.routing.node.ServerInstance;
import io.netty.util.concurrent.Promise;

public interface HealthChecker {

    Promise<Long> isHealthy(final ServerInstance instance);

}

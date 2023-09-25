package com.github.pangolin.routing.node;

import io.netty.util.concurrent.Promise;

public interface HealthChecker {

    Promise<Long> checkHealth(final Server server);

}

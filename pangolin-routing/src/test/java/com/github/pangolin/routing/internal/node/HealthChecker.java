package com.github.pangolin.routing.internal.node;

import io.netty.util.concurrent.Promise;

public interface HealthChecker {

    Promise<Long> checkHealth(final ProxyServer server);

}

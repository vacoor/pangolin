package com.github.pangolin.routing.internal.node.health;

import com.github.pangolin.routing.internal.node.ProxyServer;
import io.netty.util.concurrent.Promise;

public interface HealthChecker {

    Promise<Long> checkHealth(final ProxyServer server);

}

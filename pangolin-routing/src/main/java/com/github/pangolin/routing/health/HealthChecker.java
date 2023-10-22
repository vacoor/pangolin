package com.github.pangolin.routing.health;

import com.github.pangolin.routing.ProxyServer;
import io.netty.util.concurrent.Promise;

public interface HealthChecker {

    Promise<Long> checkHealth(final ProxyServer server);

}

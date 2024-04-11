package com.github.pangolin.routing.proxy.health;

import com.github.pangolin.routing.proxy.ProxyServer;
import io.netty.util.concurrent.Promise;

public interface HealthChecker {

    Promise<Long> checkHealth(final ProxyServer server);

}

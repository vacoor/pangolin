package com.github.pangolin.routing.internal.proxy.health;

import com.github.pangolin.routing.internal.proxy.ProxyServer2;
import io.netty.util.concurrent.Promise;

public interface HealthChecker {

    Promise<Long> checkHealth(final ProxyServer2 instance);

}

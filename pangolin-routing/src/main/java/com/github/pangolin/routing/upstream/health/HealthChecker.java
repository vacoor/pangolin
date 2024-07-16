package com.github.pangolin.routing.upstream.health;

import com.github.pangolin.routing.upstream.UpstreamServer;
import io.netty.util.concurrent.Promise;

public interface HealthChecker {

    Promise<Long> checkHealth(final UpstreamServer server);

}

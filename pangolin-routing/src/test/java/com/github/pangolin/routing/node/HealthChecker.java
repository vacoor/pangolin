package com.github.pangolin.routing.node;

import com.github.pangolin.routing.node.spi.ProxyInstance;
import io.netty.channel.EventLoopGroup;
import io.netty.util.concurrent.Promise;

/**
 *
 */
public interface HealthChecker {

    Promise<Long> ping(final ProxyInstance instance, final EventLoopGroup group);

}

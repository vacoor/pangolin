package com.github.pangolin.routing.node;

import io.netty.channel.ChannelHandler;
import io.netty.util.concurrent.GlobalEventExecutor;
import io.netty.util.concurrent.Promise;

/**
 *
 */
public class LbServiceInstance implements HealthService {
    private final String name;
    private final LoadBalancer lb;

    public LbServiceInstance(final String name, final LoadBalancer lb) {
        this.name = name;
        this.lb = lb;
    }

    public String name() {
        return name;
    }

    @Override
    public ChannelHandler newProxyHandler() {
        ServiceInstance next = lb.next(true);
        return next.newProxyHandler();
    }

    @Override
    public Promise<Long> ping() {
        final ServiceInstance next = lb.next(false);
        if (next instanceof HealthService) {
            return ((HealthService) next).ping();
        }
        return GlobalEventExecutor.INSTANCE.<Long>newPromise().setSuccess(0L);
    }

    @Override
    public boolean isPassingCheck() {
        return !lb.getReachableServers().isEmpty();
    }

    @Override
    public Stats getStats() {
        return null;
    }
}

package com.github.pangolin.routing.node;

import io.netty.channel.EventLoopGroup;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.GenericFutureListener;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

/**
 *
 */
@Slf4j
public class LoadBalancer {
    private final long healthCheckIntervalSeconds = TimeUnit.MINUTES.toSeconds(1);
    private final EventLoopGroup eventLoopGroup;

    private final List<ServiceInstance> allServers = new CopyOnWriteArrayList<>();
    private final List<ServiceInstance> upServers = new CopyOnWriteArrayList<>();
    private final List<ServiceInstance> downServers = new CopyOnWriteArrayList<>();

    public LoadBalancer(final EventLoopGroup eventLoopGroup, final List<ServiceInstance> instances) {
        this.allServers.addAll(instances);
        this.upServers.addAll(instances);
        this.eventLoopGroup = eventLoopGroup;
        this.healthCheck();
    }

    public ServiceInstance next(boolean acquire) {
        if (upServers.isEmpty()) {
            throw new IllegalStateException("No available instance found");
        }
        final List<ServiceInstance> instances = new ArrayList<>(upServers);
        instances.sort(new Comparator<ServiceInstance>() {
            @Override
            public int compare(final ServiceInstance o1, final ServiceInstance o2) {
                if (o1 instanceof HealthService && o2 instanceof HealthService) {
                    final HealthService.Stats s1 = ((HealthService) o1).getStats();
                    final HealthService.Stats s2 = ((HealthService) o2).getStats();
                    if (null == s1) {
                        return 1;
                    }
                    if (null == s2) {
                        return -1;
                    }
                    return Double.compare(s1.getAvgRt(), s2.getAvgRt());
                } else if (o1 instanceof HealthService) {
                    return -1;
                } else {
                    return 1;
                }
            }
        });
        // return instances.iterator().next();
        log.info("choose: {}", upServers);
        return upServers.get(ThreadLocalRandom.current().nextInt(Math.min(upServers.size(), 10)));
    }

    public List<ServiceInstance> getReachableServers() {
        return Collections.unmodifiableList(upServers);
    }

    private void healthCheck() {
        eventLoopGroup.scheduleWithFixedDelay(() -> healthCheck(allServers), 0, healthCheckIntervalSeconds, TimeUnit.SECONDS);
    }

    private void healthCheck(final List<ServiceInstance> instances) {
        for (ServiceInstance instance : instances) {
            if (!(instance instanceof HealthService)) {
                continue;
            }
            final HealthService healthService = (HealthService) instance;
            healthService.ping().addListener(new GenericFutureListener<Future<Long>>() {
                @Override
                public void operationComplete(final Future<Long> future) throws Exception {
                    if (future.isSuccess()) {
                        if (downServers.remove(instance)) {
                            upServers.add(instance);
                        }
                    } else {
                        if (upServers.remove(instance)) {
                            downServers.remove(instance);
                        }
                    }
                }
            });
        }
    }
}

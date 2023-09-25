package com.github.pangolin.routing.node;

import io.netty.channel.EventLoopGroup;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

/**
 *
 */
public class LoadBalancer {
    private final long healthCheckIntervalSeconds = TimeUnit.MINUTES.toSeconds(1);
    private final EventLoopGroup eventLoopGroup;

    private final List<ServerInstance> allServers = new CopyOnWriteArrayList<>();
    private final List<ServerInstance> upServers = new CopyOnWriteArrayList<>();
    private final List<ServerInstance> downServers = new CopyOnWriteArrayList<>();

    public LoadBalancer(final EventLoopGroup eventLoopGroup, final List<ServerInstance> instances) {
        this.allServers.addAll(instances);
        this.upServers.addAll(instances);
        this.eventLoopGroup = eventLoopGroup;
        this.healthCheck();
    }

    public ServerInstance next() {
        if (upServers.isEmpty()) {
            throw new IllegalStateException("No available instance found");
        }
        return upServers.get(ThreadLocalRandom.current().nextInt(upServers.size()));
    }

    public List<ServerInstance> getReachableServers() {
        return Collections.unmodifiableList(upServers);
    }

    private void healthCheck() {
        eventLoopGroup.scheduleWithFixedDelay(() -> healthCheck(allServers), 0, healthCheckIntervalSeconds, TimeUnit.SECONDS);
    }

    private void healthCheck(final List<ServerInstance> instances) {
        for (ServerInstance instance : instances) {
            if (instance.isPassingCheck()) {
                if (downServers.remove(instance)) {
                    upServers.add(instance);
                }
            } else {
                if (upServers.remove(instance)) {
                    downServers.remove(instance);
                }
            }
        }
    }
}

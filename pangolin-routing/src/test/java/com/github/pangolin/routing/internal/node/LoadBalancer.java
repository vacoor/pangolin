package com.github.pangolin.routing.internal.node;

import com.github.pangolin.routing.internal.node.health.HealthChecker;
import com.github.pangolin.routing.internal.node.util.AvgMinMaxCounter;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.GenericFutureListener;
import lombok.extern.slf4j.Slf4j;

import java.util.*;
import java.util.concurrent.*;

/**
 *
 */
@Slf4j
public class LoadBalancer {
    private final String name;
    private final HealthChecker healthChecker;
    private final long healthCheckIntervalSeconds = TimeUnit.MINUTES.toSeconds(1);

    private final List<ProxyServer> allServers = new CopyOnWriteArrayList<>();
    private final List<ProxyServer> upServers = new CopyOnWriteArrayList<>();
    private final List<ProxyServer> downServers = new CopyOnWriteArrayList<>();
    private final Map<ProxyServer, ServerStats> lbServerStats = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler;

    public LoadBalancer(final String name, final HealthChecker healthChecker, final List<ProxyServer> instances, final ScheduledExecutorService scheduler) {
        this.name = name;
        this.healthChecker = healthChecker;

        this.allServers.addAll(instances);
        for (ProxyServer instance : instances) {
            lbServerStats.put(instance, new ServerStats(instance));
        }
        this.downServers.addAll(instances);
        this.scheduler = scheduler;
        this.startHealthCheck();
    }

    public String getName() {
        return name;
    }

    private void startHealthCheck() {
        if (null == healthChecker) {
            return;
        }
        scheduler.scheduleWithFixedDelay(() -> runHealthCheck(scheduler), 0, healthCheckIntervalSeconds, TimeUnit.SECONDS);
    }

    private void runHealthCheck(final ScheduledExecutorService scheduler) {
        final List<ProxyServer> instances = new ArrayList<>(allServers);
        for (ProxyServer instance : instances) {
            healthChecker.checkHealth(instance).addListener(new GenericFutureListener<Future<Long>>() {
                @Override
                public void operationComplete(final Future<Long> future) throws Exception {
                    if (future.isSuccess()) {
                        addServerRt(instance, future.get());
                        if (downServers.remove(instance)) {
                            log.info("Instance UP: {}, response time: {}ms", instance.getName(), future.get());
                            upServers.add(instance);
                        }
                    } else {
                        if (upServers.remove(instance)) {
                            log.info("Instance DOWN: {}", instance.getName());
                            downServers.remove(instance);
                        }
                    }
                }
            });
        }
    }


    public ProxyServer next(boolean acquire) {
        if (upServers.isEmpty()) {
            throw new IllegalStateException("No available instance found");
        }
        final List<ProxyServer> instances = new ArrayList<>(upServers);
        instances.sort(new Comparator<ProxyServer>() {
            @Override
            public int compare(final ProxyServer o1, final ProxyServer o2) {
                return Double.compare(getServerAvgRt(o1), getServerAvgRt(o2));
            }
        });
        // return instances.iterator().next();
        return upServers.get(ThreadLocalRandom.current().nextInt(Math.min(upServers.size(), 5)));
    }

    public List<ProxyServer> getReachableServers() {
        return Collections.unmodifiableList(upServers);
    }

    double getServerAvgRt(final ProxyServer server) {
        final ServerStats stats = lbServerStats.get(server);
        return null != stats ? stats.rt.getAvg() : Double.MAX_VALUE;
    }

    void addServerRt(final ProxyServer server, final long rt) {
        final ServerStats stats = lbServerStats.get(server);
        if (null != stats) {
            stats.addRt(rt);
        }
    }

    class ServerStats {
        private final ProxyServer server;
        private final AvgMinMaxCounter rt = new AvgMinMaxCounter("ResponseTime");

        ServerStats(final ProxyServer server) {
            this.server = server;
        }

        ServerStats addRt(final long ms) {
            rt.addDataPoint(ms);
            return this;
        }
    }
}

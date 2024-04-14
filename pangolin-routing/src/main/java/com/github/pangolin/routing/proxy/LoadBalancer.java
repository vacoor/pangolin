package com.github.pangolin.routing.proxy;

import com.github.pangolin.routing.proxy.health.HealthChecker;
import freework.util.StringUtils2;
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
                            upServers.add(instance);
                        }
                        log.info("[Health Check] {} response time: {}ms", instance.getName(), future.get());
                    } else {
                        if (upServers.remove(instance)) {
                            downServers.add(instance);
                        }
                        log.info("[Health Check] {} down: {}", instance.getName(), future.cause().getMessage());
                    }
                }
            });
        }
    }


    public ProxyServer next(boolean acquire) {
        List<ProxyServer> serversToUse = upServers;
        if (serversToUse.isEmpty()) {
            log.warn("No available instance found");
            serversToUse = allServers;
        }
        final List<ProxyServer> instances = new ArrayList<>(serversToUse);
        instances.sort(new Comparator<ProxyServer>() {
            @Override
            public int compare(final ProxyServer o1, final ProxyServer o2) {
                return Double.compare(getServerAvgRt(o1), getServerAvgRt(o2));
            }
        });

//        final int index = ThreadLocalRandom.current().nextInt(Math.min(instances.size(), 5));
        final int index = ThreadLocalRandom.current().nextInt(Math.min(instances.size(), 1));

        /*
        int nameLength = 0;
        for (ProxyServer instance : instances) {
            nameLength = Math.max(instance.getName().length(), nameLength);
        }

        final StringBuilder buff = new StringBuilder();
        buff.append("----------------\r\n");
        buff.append(lpad("Instance", 3 + nameLength)).append(lpad("Avg Response Time(ms)", 25));
        for (int i = 0; i < instances.size(); i++) {
            final ProxyServer instance = instances.get(i);
            final String art = String.valueOf(getServerAvgRt(instance));
            buff.append("\r\n");
            if (index == i) {
                buff.append("-> ").append(lpad(instance.getName(), nameLength));
            } else {
                buff.append(lpad(instance.getName(), 3 + nameLength));
            }
            buff.append(lpad(art, 25));
        }
        buff.append("\r\n----------------");

        log.info("{}", buff);
        */

        // return instances.iterator().next();
        return instances.get(index);
    }

    private String lpad(final String text, final int length) {
        return StringUtils2.pad(text, ' ', length, true);
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

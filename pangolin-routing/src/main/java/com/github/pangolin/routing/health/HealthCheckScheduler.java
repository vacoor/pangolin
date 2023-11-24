package com.github.pangolin.routing.health;

import com.github.pangolin.routing.proxy.ProxyServer;

import java.util.Map;
import java.util.concurrent.*;

public class HealthCheckScheduler {
    private final HealthChecker healthChecker;
    private final ScheduledExecutorService scheduler;
    private final long healthCheckIntervalMs = TimeUnit.SECONDS.toMillis(10);
    private final Map<ProxyServer, ScheduledFuture<?>> healthChecks = new ConcurrentHashMap<>();

    public HealthCheckScheduler(final ScheduledExecutorService scheduler, final HealthChecker healthChecker) {
        this.scheduler = scheduler;
        this.healthChecker = healthChecker;
    }

    public void add(final ProxyServer instance) {
        final ScheduledFuture<?> task = scheduler.scheduleAtFixedRate(
                new HealthCheckTask(instance, healthChecker),
                0, healthCheckIntervalMs, TimeUnit.MILLISECONDS
        );
        final ScheduledFuture<?> previousTask = healthChecks.put(instance, task);
        if (null != previousTask) {
            previousTask.cancel(true);
        }
    }

    public void remove(final ProxyServer instance) {
        final ScheduledFuture<?> task = healthChecks.get(instance);
        if (null != task) {
            task.cancel(true);
        }
        healthChecks.remove(instance);
    }

    private void healthCheck(final ProxyServer instance, final HealthChecker healthChecker) {
        healthChecker.checkHealth(instance);
        // TODO
    }

    private class HealthCheckTask implements Runnable {
        private final ProxyServer instance;
        private final HealthChecker healthChecker;

        private HealthCheckTask(final ProxyServer instance, final HealthChecker healthChecker) {
            this.instance = instance;
            this.healthChecker = healthChecker;
        }

        @Override
        public void run() {
            healthCheck(instance, healthChecker);
        }
    }

}
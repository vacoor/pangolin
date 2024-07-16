package com.github.pangolin.routing.upstream.health;

import com.github.pangolin.routing.upstream.UpstreamServer;

import java.util.Map;
import java.util.concurrent.*;

public class HealthCheckScheduler {
    private final HealthChecker healthChecker;
    private final ScheduledExecutorService scheduler;
    private final long healthCheckIntervalMs = TimeUnit.MINUTES.toMillis(10);
    private final Map<UpstreamServer, ScheduledFuture<?>> healthChecks = new ConcurrentHashMap<>();

    public HealthCheckScheduler(final ScheduledExecutorService scheduler, final HealthChecker healthChecker) {
        this.scheduler = scheduler;
        this.healthChecker = healthChecker;
    }

    public void add(final UpstreamServer instance) {
        final ScheduledFuture<?> task = scheduler.scheduleAtFixedRate(
                new HealthCheckTask(instance, healthChecker),
                0, healthCheckIntervalMs, TimeUnit.MILLISECONDS
        );
        final ScheduledFuture<?> previousTask = healthChecks.put(instance, task);
        if (null != previousTask) {
            previousTask.cancel(true);
        }
    }

    public void remove(final UpstreamServer instance) {
        final ScheduledFuture<?> task = healthChecks.get(instance);
        if (null != task) {
            task.cancel(true);
        }
        healthChecks.remove(instance);
    }

    private void healthCheck(final UpstreamServer instance, final HealthChecker healthChecker) {
        healthChecker.checkHealth(instance);
        // TODO
    }

    private class HealthCheckTask implements Runnable {
        private final UpstreamServer instance;
        private final HealthChecker healthChecker;

        private HealthCheckTask(final UpstreamServer instance, final HealthChecker healthChecker) {
            this.instance = instance;
            this.healthChecker = healthChecker;
        }

        @Override
        public void run() {
            healthCheck(instance, healthChecker);
        }
    }

}
package com.github.pangolin.routing.node;

import com.github.pangolin.routing.node.spi.ProxyInstance;
import com.github.pangolin.routing.node.util.AvgMinMaxCounter;
import io.netty.channel.ChannelHandler;
import io.netty.channel.EventLoopGroup;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.GenericFutureListener;
import io.netty.util.concurrent.Promise;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 *
 */
@Slf4j
public class HealthServiceFactory {
    private final EventLoopGroup loop;
    private final HealthChecker healthChecker;
    private final List<HealthService> instances = new CopyOnWriteArrayList<>();
    private final long healthCheckIntervalSeconds = TimeUnit.MINUTES.toSeconds(1);
    private final AtomicBoolean healthCheckStarted = new AtomicBoolean(false);

    public HealthServiceFactory(final EventLoopGroup loop, final HealthChecker healthChecker) {
        this.loop = loop;
        this.healthChecker = healthChecker;
    }

    public HealthService getInstance(final ProxyInstance instance) {
        return add(new Instance(instance));
    }

    private HealthService add(final HealthService instance) {
        instance.ping();
        instances.add(instance);
//        ensureHeathCheckStart();
        return instance;
    }

    private void ensureHeathCheckStart() {
//        if (healthCheckStarted.compareAndSet(false, true)) {
//            loop.scheduleWithFixedDelay(this::healthCheck, healthCheckIntervalSeconds, healthCheckIntervalSeconds, TimeUnit.SECONDS);
//        }
    }

    private void healthCheck() {
        for (final HealthService instance : instances) {
            instance.ping();
        }
    }

    private Promise<Long> ping(final ProxyInstance instance) {
        return healthChecker.ping(instance, loop);
    }


    private class Instance implements HealthService {
        private final AvgMinMaxCounter rt = new AvgMinMaxCounter("Response-Time");
        private final AtomicBoolean passingCheck = new AtomicBoolean(false);
        private final ProxyInstance instance;

        private Instance(final ProxyInstance instance) {
            this.instance = instance;
        }

        @Override
        public String name() {
            return instance.getName();
        }

        @Override
        public Promise<Long> ping() {
            return HealthServiceFactory.this.ping(instance).addListener(new GenericFutureListener<Future<Long>>() {
                @Override
                public void operationComplete(final Future<Long> future) throws Exception {
                    if (future.isSuccess()) {
                        rt.addDataPoint(future.get());
                        if (passingCheck.compareAndSet(false, true)) {
                            log.info("Instance UP: {}, response time: {}ms, avg: {}ms, min: {}ms, max: {}", instance, future.get(), rt.getAvg(), rt.getMin(), rt.getMax());
                        }
                    } else {
                        rt.addDataPoint(Long.MAX_VALUE);
                        if (passingCheck.compareAndSet(true, false)) {
                            log.info("Instance DOWN: {}, response time: {}ms, avg: {}ms, min: {}ms, max: {}", instance, future.get(), rt.getAvg(), rt.getMin(), rt.getMax());
                        }
                    }
                }
            });
        }

        @Override
        public boolean isPassingCheck() {
            return passingCheck.get();
        }

        @Override
        public Stats getStats() {
            return new Stats() {
                @Override
                public double getAvgRt() {
                    return rt.getAvg();
                }

                @Override
                public long getMinRt() {
                    return rt.getMin();
                }

                @Override
                public long getMaxRt() {
                    return rt.getMax();
                }
            };
        }

        @Override
        public ChannelHandler newProxyHandler() {
            return instance.newProxyHandler();
        }
    }

}

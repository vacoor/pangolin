package com.github.pangolin.routing.node;

import com.github.pangolin.routing.node.heath.UrlTestHealthChecker;
import com.github.pangolin.routing.node.spi.ProxyInstance;
import com.github.pangolin.routing.node.util.AvgMinMaxCounter;
import io.netty.channel.ChannelHandler;
import io.netty.channel.EventLoopGroup;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.GenericFutureListener;
import io.netty.util.concurrent.Promise;
import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 */
@Slf4j
public class ServerInstanceImpl implements ServerInstance {
    private final ProxyInstance delegate;
    private final EventLoopGroup group;
    private final UrlTestHealthChecker healthCheck;
    private final AtomicBoolean started = new AtomicBoolean(false);
    private final AtomicReference<Status> status = new AtomicReference<>(Status.UP);
    private final AvgMinMaxCounter latency = new AvgMinMaxCounter("latency");

    public ServerInstanceImpl(final ProxyInstance instance, final EventLoopGroup group, final UrlTestHealthChecker healthChecker) {
        this.delegate = instance;
        this.group = group;
        this.healthCheck = healthChecker;
    }

    public String name() {
        return delegate.getName();
    }

    public ChannelHandler newProxyHandler() {
        return delegate.newProxyHandler();
    }

    public ServerInstanceImpl start() {
        if (started.compareAndSet(false, true)) {
            group.scheduleWithFixedDelay(() -> healthCheck(delegate, group), 0, 10, TimeUnit.MINUTES);
        }
        return this;
    }

    public boolean isPassingCheck() {
        return Status.UP.equals(status.get());
    }

    protected Promise<Long> healthCheck(final ProxyInstance instance, final EventLoopGroup checkGroup) {
        return healthCheck.heathCheck(instance, checkGroup).addListener(new GenericFutureListener<Future<Long>>() {
            @Override
            public void operationComplete(final Future<Long> future) throws Exception {
                if (future.isSuccess()) {
                    latency.addDataPoint(future.get());
                    if (status.compareAndSet(Status.DOWN, Status.UP)) {
                        log.info("Instance UP: {}, response time: {}ms, avg: {}ms, min: {}ms, max: {}", instance,future.get(), latency.getAvg(), latency.getMin(), latency.getMax());
                    }
                } else {
                    latency.addDataPoint(Long.MAX_VALUE);
                    if (status.compareAndSet(Status.UP, Status.DOWN)) {
                        log.info("Instance Down: {}, {}", instance, future.cause());
                    }
                }
            }
        });
    }

    enum Status { UP, DOWN }
}

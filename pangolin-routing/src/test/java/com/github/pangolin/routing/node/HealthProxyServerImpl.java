package com.github.pangolin.routing.node;

import com.github.pangolin.routing.node.heath.UrlTestHealthCheck;
import com.github.pangolin.routing.node.spi.ProxyServer;
import io.netty.channel.ChannelHandler;
import io.netty.channel.EventLoopGroup;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.GenericFutureListener;
import io.netty.util.concurrent.Promise;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 */
public class HealthProxyServerImpl implements HealthProxyServer {
    private final ProxyServer delegate;
    private final EventLoopGroup group;
    private final UrlTestHealthCheck healthCheck;
    private final AtomicBoolean started = new AtomicBoolean(false);
    private final AtomicReference<Status> status = new AtomicReference<>(Status.UP);
    private final AtomicReference<Long> time = new AtomicReference<>(-1L);

    public HealthProxyServerImpl(final ProxyServer proxyServer, final EventLoopGroup group, final UrlTestHealthCheck healthCheck) {
        this.delegate = proxyServer;
        this.group = group;
        this.healthCheck = healthCheck;
    }

    public String name() {
        return delegate.name();
    }

    public ChannelHandler newProxyHandler() {
        return delegate.newProxyHandler();
    }

    public HealthProxyServerImpl start() {
        if (started.compareAndSet(false, true)) {
            group.scheduleWithFixedDelay(() -> ping(delegate, group), 0, 10, TimeUnit.MINUTES);
        }
        return this;
    }

    public boolean isPassingCheck() {
        return Status.UP.equals(status.get());
    }

    protected Promise<Long> ping(final ProxyServer proxy, final EventLoopGroup checkGroup) {
        return healthCheck.heathCheck(proxy, checkGroup).addListener(new GenericFutureListener<Future<Long>>() {
            @Override
            public void operationComplete(final Future<Long> future) throws Exception {
                if (future.isSuccess()) {
                    time.set(future.get());
                    status.compareAndSet(Status.DOWN, Status.UP);
                } else {
                    status.compareAndSet(Status.UP, Status.DOWN);
                    time.set(-1L);
                }
            }
        });
    }

    enum Status { UP, DOWN }
}

package com.github.pangolin.routing.internal.server;

import com.github.pangolin.routing.internal.server.heathcheck.UrlTestHealthCheck;
import io.netty.channel.EventLoopGroup;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.GenericFutureListener;
import io.netty.util.concurrent.Promise;

import java.util.concurrent.atomic.AtomicReference;

/**
 */
public class AbstractProxyServer {
    private UrlTestHealthCheck healthCheck;
    private final AtomicReference<Status> status = new AtomicReference<>(Status.UP);

    public boolean isPassingCheck() {
        return Status.UP.equals(status.get());
    }

    protected Promise<Void> ping(final ProxyServer proxy, final EventLoopGroup checkGroup) {
        return healthCheck.heathCheck(proxy, checkGroup).addListener(new GenericFutureListener<Future<? super Void>>() {
            @Override
            public void operationComplete(final Future<? super Void> future) throws Exception {
                if (future.isSuccess()) {
                    status.compareAndSet(Status.DOWN, Status.UP);
                } else {
                    status.compareAndSet(Status.UP, Status.DOWN);
                }
            }
        });
    }

    enum Status { UP, DOWN }
}

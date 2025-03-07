package com.github.pangolin.routing.upstream.stats;

import com.github.pangolin.routing.upstream.Upstream;
import com.netflix.loadbalancer.LoadBalancerStats;
import com.netflix.loadbalancer.Server;
import com.netflix.loadbalancer.ServerStats;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.Arrays;

public class StatsUpstream extends Server implements Upstream {
    private final Upstream delegate;
    private final LoadBalancerStats stats;

    public StatsUpstream(final Upstream delegate, final LoadBalancerStats stats) {
        super(delegate.name());
        this.delegate = delegate;
        this.stats = stats;
    }

    @Override
    public String name() {
        return delegate.name();
    }

    @Override
    public SocketAddress address() {
        return delegate.address();
    }

    @Override
    public boolean isVirtual() {
        return delegate.isVirtual();
    }

    @Override
    public ChannelHandler newSocketProxyHandler(final InetSocketAddress destination) {
        return wrap(delegate.newSocketProxyHandler(destination));
    }

    @Override
    public ChannelHandler[] newSocketProxyHandlers(final InetSocketAddress destination) {
        return Arrays.stream(delegate.newSocketProxyHandlers(destination))
                .map(this::wrap)
                .toArray(ChannelHandler[]::new);
    }


    @Override
    public ChannelHandler newDatagramProxyHandler(final InetSocketAddress destination) {
        return delegate.newDatagramProxyHandler(destination);
    }

    private ChannelHandler wrap(final ChannelHandler h) {
        if (null == h) {
            return null;
        }
        final ServerStats serverStats = stats.getSingleServerStat(this);
        return new ChannelDuplexHandler() {
            private volatile long requestTime;

            @Override
            public void handlerAdded(final ChannelHandlerContext ctx) throws Exception {
                ctx.pipeline().addBefore(ctx.name(), null, h);
            }

            @Override
            public void channelActive(final ChannelHandlerContext ctx) throws Exception {
                super.channelActive(ctx);
                serverStats.incrementOpenConnectionsCount();
                serverStats.clearSuccessiveConnectionFailureCount();
            }

            @Override
            public void channelInactive(final ChannelHandlerContext ctx) throws Exception {
                super.channelInactive(ctx);
                serverStats.decrementOpenConnectionsCount();
            }

            @Override
            public void flush(final ChannelHandlerContext ctx) throws Exception {
                super.flush(ctx);
                serverStats.incrementActiveRequestsCount();
                requestTime = System.nanoTime();
            }


            @Override
            public void channelReadComplete(final ChannelHandlerContext ctx) throws Exception {
                super.channelReadComplete(ctx);
                serverStats.decrementActiveRequestsCount();
                serverStats.noteResponseTime(System.nanoTime() - requestTime / 1000D);
            }

            @Override
            public String toString() {
                return h.toString();
            }
        };
    }

    @Override
    public int hashCode() {
        return delegate.hashCode();
    }

    @Override
    public String toString() {
        return delegate.toString();
    }
}
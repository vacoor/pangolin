package com.github.pangolin.routing.proxy;

import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.GenericFutureListener;

import java.net.InetSocketAddress;
import java.net.SocketAddress;

public class StatProxyServer implements ProxyServer {
    private final ProxyServer delegate;

    public StatProxyServer(final ProxyServer delegate) {
        this.delegate = delegate;
    }

    @Override
    public String getName() {
        return delegate.getName();
    }

    @Override
    public ChannelHandler newProxyHandler(final InetSocketAddress sa) {
        ChannelHandler handler = delegate.newProxyHandler(sa);
        if (handler instanceof StatChannelHandler) {
            return handler;
        }
        return new StatChannelHandler(handler, delegate);
    }

    private class StatChannelHandler extends ChannelDuplexHandler {
        private final ChannelHandler delegate;
        private final ProxyServer proxyServer;

        private StatChannelHandler(final ChannelHandler delegate, final ProxyServer proxyServer) {
            this.delegate = delegate;
            this.proxyServer = proxyServer;
        }

        @Override
        public void handlerAdded(final ChannelHandlerContext ctx) throws Exception {
            ctx.pipeline().addBefore(ctx.name(), null, delegate);
        }

        @Override
        public void handlerRemoved(final ChannelHandlerContext ctx) throws Exception {
            ctx.pipeline().remove(delegate);
        }

        @Override
        public void connect(final ChannelHandlerContext ctx, final SocketAddress remoteAddress, final SocketAddress localAddress, final ChannelPromise promise) throws Exception {
            final long sinceMs = System.currentTimeMillis();
            promise.addListener(new GenericFutureListener<Future<? super Void>>() {
                @Override
                public void operationComplete(final Future<? super Void> future) throws Exception {
                    if (future.isSuccess()) {
                        final long elapsedMs = System.currentTimeMillis() - sinceMs;
                        System.out.println(getName() + ": " + elapsedMs);
                    }
                }
            });
            super.connect(ctx, remoteAddress, localAddress, promise);
        }
    }

}
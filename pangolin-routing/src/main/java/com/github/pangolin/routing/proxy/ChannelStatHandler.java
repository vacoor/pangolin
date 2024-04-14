package com.github.pangolin.routing.proxy;

import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.GenericFutureListener;

import java.net.SocketAddress;

public class ChannelStatHandler extends ChannelDuplexHandler {
    private final ProxyServer proxyServer;

    public ChannelStatHandler(final ProxyServer proxyServer) {
        this.proxyServer = proxyServer;
    }

    @Override
    public void connect(final ChannelHandlerContext ctx, final SocketAddress remoteAddress, final SocketAddress localAddress, final ChannelPromise promise) throws Exception {
        final long sinceMs = System.currentTimeMillis();
        promise.addListener(new GenericFutureListener<Future<? super Void>>() {
            @Override
            public void operationComplete(final Future<? super Void> future) throws Exception {
                if (future.isSuccess()) {
                    final long elapsedMs = System.currentTimeMillis() - sinceMs;
                    System.out.println(proxyServer.getName() + ": " + elapsedMs);
                }
            }
        });
        super.connect(ctx, remoteAddress, localAddress, promise);
    }
}
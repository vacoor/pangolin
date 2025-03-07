package com.github.pangolin.routing.server;

import com.github.pangolin.routing.support.StandardSocketChannelFactory;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.GenericFutureListener;
import io.netty.util.concurrent.GlobalEventExecutor;
import io.netty.util.concurrent.Promise;

import java.net.InetSocketAddress;
import java.util.concurrent.locks.LockSupport;

public class MpSelector {
    public static void main(String[] args) {
        final StandardSocketChannelFactory f = new StandardSocketChannelFactory(null);
        final Promise<Channel> promise = GlobalEventExecutor.INSTANCE.newPromise();
        final NioEventLoopGroup g = new NioEventLoopGroup();


        InetSocketAddress[] addrs = new InetSocketAddress[] {
                new InetSocketAddress("180.101.49.44", 443),
                new InetSocketAddress("119.29.104.70", 443),
                new InetSocketAddress("192.168.1.201", 22),
                new InetSocketAddress("199.168.1.201", 22),
        };
        ChannelFuture[] fs = new ChannelFuture[addrs.length];

        promise.addListener(new GenericFutureListener<Future<? super Channel>>() {
            @Override
            public void operationComplete(final Future<? super Channel> future) throws Exception {
                System.out.println(future.isSuccess() + " -> " + future.get());
                for (ChannelFuture channelFuture : fs) {
                    System.out.println(channelFuture.isSuccess() + " -> " + channelFuture.channel().isOpen());
                }
            }
        });
        for (int i = 0; i < addrs.length; i++) {
            fs[i] = f.open(addrs[i], 100, true, g, new ChannelHandlerAdapter() {
            }).addListener(new ChannelFutureListener() {
                @Override
                public void operationComplete(final ChannelFuture future) throws Exception {
                    if (promise.isSuccess() || (future.isSuccess() && !promise.trySuccess(future.channel()))) {
                        future.channel().close();
                    }
                }
            });
        }
        LockSupport.park();
    }


    private static ChannelFuture select(final InetSocketAddress addr, final int connTimeoutMs,
                                        final boolean autoRead, final EventLoopGroup group, final ChannelHandler handler) {
        final Promise<ChannelFuture> promise = GlobalEventExecutor.INSTANCE.newPromise();

        try {
            return promise.await().get();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

}
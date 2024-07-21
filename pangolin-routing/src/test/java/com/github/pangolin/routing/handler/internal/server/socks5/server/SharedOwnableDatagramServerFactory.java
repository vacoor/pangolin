package com.github.pangolin.routing.handler.internal.server.socks5.server;

import com.github.pangolin.routing.handler.internal.server.Socks5DatagramServerHandler;
import com.github.pangolin.routing.handler.internal.server.support.DatagramChannelFactory;
import com.github.pangolin.routing.handler.internal.server.support.StandardDatagramChannelFactory;
import com.google.common.collect.Maps;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.DatagramChannel;
import io.netty.channel.socket.nio.NioDatagramChannel;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.GenericFutureListener;
import io.netty.util.concurrent.Promise;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.locks.ReentrantLock;

public class SharedOwnableDatagramServerFactory implements OwnableDatagramServerFactory {
    private final ReentrantLock lock = new ReentrantLock();

    private volatile ChannelFuture sharedDatagram;
    private final ConcurrentMap<InetAddress, OwnableDatagramServer> ownableDatagrams = Maps.newConcurrentMap();
    private final DatagramChannelFactory factory = new StandardDatagramChannelFactory();

    @Override
    public OwnableDatagramServer bind(final InetAddress owner) throws InterruptedException {
        if (null == sharedDatagram) {
            waitStarted();
        }

        return ownableDatagrams.computeIfAbsent(owner, own -> new OwnableDatagramServer() {
            @Override
            public InetAddress owner() {
                return own;
            }

            @Override
            public InetSocketAddress localAddress() {
                return (InetSocketAddress) sharedDatagram.channel().localAddress();
            }

            @Override
            public Promise<Void> shutdownGracefully() {
                ownableDatagrams.remove(own);
                if (!ownableDatagrams.isEmpty()) {
                    return null;
                }
                lock.lock();
                try {
                    if (ownableDatagrams.isEmpty()) {
                        ChannelFuture me = sharedDatagram;
                        me.channel().close().addListener(new GenericFutureListener<Future<? super Void>>() {
                            @Override
                            public void operationComplete(final Future<? super Void> future) throws Exception {
                                me.channel().eventLoop().shutdownGracefully();
                            }
                        });
                        sharedDatagram = null;
                    }
                } finally {
                    lock.unlock();
                }
                return null;
            }
        });
    }

    private void waitStarted() throws InterruptedException {
        lock.lockInterruptibly();
        try {
            if (null == sharedDatagram) {
                sharedDatagram = bind(0).sync();
            }
        } finally {
            lock.unlock();
        }
    }

    private ChannelFuture bind(final int listenPort) {
//        Socks5DatagramServerHandler utildpServerHandler = h;
//        new Socks5DatagramServerHandler()
        final Bootstrap udpBootstrap = new Bootstrap();
        udpBootstrap.group(new NioEventLoopGroup());
        udpBootstrap.channel(NioDatagramChannel.class);
        udpBootstrap.option(ChannelOption.SO_BROADCAST, false);
        udpBootstrap.handler(new ChannelInitializer<DatagramChannel>() {
            @Override
            protected void initChannel(final DatagramChannel ch) throws Exception {
//                ch.pipeline().addLast(udpServerHandler);
                ch.pipeline().addLast(new Socks5DatagramServerHandler(factory));
            }
        });
        return udpBootstrap.bind(listenPort);
    }

    public static void main(String[] args) throws UnknownHostException, InterruptedException {
        SharedOwnableDatagramServerFactory factory = new SharedOwnableDatagramServerFactory();
        OwnableDatagramServer bind = factory.bind(InetAddress.getLocalHost());
        InetSocketAddress address = bind.localAddress();
        bind.shutdownGracefully();
        System.out.println(address);
    }
}
package com.github.pangolin.routing.handler.internal.server;

import com.github.pangolin.routing.handler.internal.server.support.DatagramChannelFactory;
import com.github.pangolin.routing.handler.internal.server.support.StandardDatagramChannelFactory;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.socket.nio.NioDatagramChannel;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.GenericFutureListener;

import java.net.InetSocketAddress;

@Deprecated
public class DefaultSocks5DatagramServerFactory implements Socks5DatagramServerFactory {
    private final DatagramChannelFactory factory;

    public DefaultSocks5DatagramServerFactory() {
        this(new StandardDatagramChannelFactory());
    }

    public DefaultSocks5DatagramServerFactory(final DatagramChannelFactory factory) {
        this.factory = factory;
    }

    @Override
    public ChannelFuture createServer(final Channel parent, final InetSocketAddress sender) {
        return new Bootstrap()
//                .group(new NioEventLoopGroup())
                .group(parent.eventLoop())
                .channel(NioDatagramChannel.class)
                .option(ChannelOption.SO_BROADCAST, false)
                .handler(new ChannelInitializer<Channel>() {
                    @Override
                    protected void initChannel(final Channel ch) throws Exception {
                        ch.pipeline().addLast(new Socks5ServerDatagramHandler(sender, factory));
                    }
                }).bind(0);
    }

    public static void main(String[] args) throws Exception {
        ChannelFuture c = new DefaultSocks5DatagramServerFactory().createServer(null, new InetSocketAddress("127.0.0.1", 0));
        c.sync().addListener(new GenericFutureListener<Future<? super Void>>() {
            @Override
            public void operationComplete(final Future<? super Void> future) throws Exception {
                System.out.println(c.channel().localAddress());
            }
        });
        c.sync().channel().closeFuture().await();
    }
}
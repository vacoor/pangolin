package com.github.pangolin.routing.server;

import com.github.pangolin.routing.handler.internal.server.Socks5ProxyServerHandler;
import com.github.pangolin.routing.handler.internal.server.Socks5ServerDatagramDemultiplexer;
import com.github.pangolin.routing.support.DatagramChannelFactory;
import com.github.pangolin.routing.support.StandardDatagramChannelFactory;
import com.github.pangolin.server.NettyServer;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioDatagramChannel;
import lombok.extern.slf4j.Slf4j;

import javax.net.ssl.SSLException;
import java.net.InetSocketAddress;
import java.security.cert.CertificateException;

@Slf4j
public class SocksServerTest {
    public static void main(String[] args) throws InterruptedException, SSLException, CertificateException {
        NettyServer server = new NettyServer(1080);
        server.start(true, new ChannelInitializer<SocketChannel>() {
            @Override
            protected void initChannel(final SocketChannel ch) throws Exception {
                ch.pipeline().addLast(new Socks5ProxyServerHandler());
            }
        }).addListener(new ChannelFutureListener() {
            @Override
            public void operationComplete(final ChannelFuture future) throws Exception {
                if (future.isSuccess()) {
                    future.channel().attr(Socks5ProxyServerHandler.UDP_CHANNEL_KEY).set(c(new StandardDatagramChannelFactory()));
                    System.out.println(future.channel().localAddress());
                }
            }
        }).channel().closeFuture().sync();

    }

    private static ChannelFuture c(final DatagramChannelFactory datagramChannelFactory) {
        return new Bootstrap()
                .group(new NioEventLoopGroup())
//            .group(parent.eventLoop())
                .channel(NioDatagramChannel.class)
                .option(ChannelOption.SO_BROADCAST, false)
                .handler(new ChannelInitializer<Channel>() {
                    @Override
                    protected void initChannel(final Channel ch) throws Exception {
                        ch.pipeline().addLast(new Socks5ServerDatagramDemultiplexer(datagramChannelFactory));
                    }
                }).bind(1082).addListener(new ChannelFutureListener() {
                    @Override
                    public void operationComplete(ChannelFuture future) throws Exception {
                        if (future.isSuccess()) {
                            InetSocketAddress localAddress = (InetSocketAddress) future.channel().localAddress();
                            System.out.println("UDP:" + future.channel().localAddress());
                            log.info("SOCKS5 UDP started on port: {} ({})", localAddress.getPort(), localAddress);
                        } else {
                            future.cause().printStackTrace();
                        }
                        System.out.println("UDP");
                    }
                });
    }
}
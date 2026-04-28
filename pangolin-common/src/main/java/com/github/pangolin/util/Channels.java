package com.github.pangolin.util;

import io.netty.bootstrap.Bootstrap;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelOption;
import io.netty.channel.ChannelPromise;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import io.netty.handler.ssl.util.SelfSignedCertificate;
import io.netty.resolver.AddressResolverGroup;

import javax.net.ssl.SSLException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.security.cert.CertificateException;

public class Channels {

    public static ChannelFuture listen(final String listenHost, final int listenPort, final boolean autoRead,
                                       final EventLoopGroup bossGroup, final EventLoopGroup workerGroup,
                                       final ChannelHandler initializer) throws InterruptedException {
        return listen(listenHost, listenPort, autoRead, bossGroup, workerGroup, null, initializer);
    }

    public static ChannelFuture listen(final String listenHost, final int listenPort, final boolean autoRead,
        final EventLoopGroup bossGroup, final EventLoopGroup workerGroup,
        final ChannelHandler serverHandler, final ChannelHandler initializer) throws InterruptedException {
        return listen(createSocketAddress(listenHost, listenPort), autoRead, bossGroup, workerGroup, serverHandler, initializer);
    }

    public static ChannelFuture listen(final SocketAddress listenAddr, final boolean autoRead, final EventLoopGroup bossGroup, final EventLoopGroup workerGroup, final ChannelHandler initializer) throws InterruptedException {
      return listen(listenAddr, autoRead, bossGroup, workerGroup, null, initializer);
    }

    public static ChannelFuture listen(final SocketAddress listenAddr, final boolean autoRead,
                                       final EventLoopGroup bossGroup, final EventLoopGroup workerGroup,
                                       final ChannelHandler serverHandler, final ChannelHandler initializer) throws InterruptedException {
        final ServerBootstrap serverBootstrap = new ServerBootstrap();
        serverBootstrap.option(ChannelOption.SO_REUSEADDR, true);
//        serverBootstrap.option(ChannelOption.SO_RCVBUF, 32 * 1024);// 读缓冲区为32k
        serverBootstrap.childOption(ChannelOption.TCP_NODELAY, true);
        serverBootstrap.childOption(ChannelOption.SO_KEEPALIVE, true);
        serverBootstrap.childOption(ChannelOption.AUTO_READ, autoRead);
        serverBootstrap.group(bossGroup, workerGroup).channel(NioServerSocketChannel.class);
        if (null != serverHandler) {
            serverBootstrap.handler(serverHandler);
        }
        serverBootstrap.childHandler(initializer);
        return serverBootstrap.bind(listenAddr);
    }

    public static ChannelFuture open(final String hostname, final int port, final boolean autoRead, final EventLoopGroup group, final ChannelHandler initializer) throws InterruptedException {
        return open(createSocketAddress(hostname, port), null, 10000, autoRead, group, initializer);
    }

    public static ChannelFuture open(final SocketAddress remoteAddress, final boolean autoRead, final EventLoopGroup group, final ChannelHandler initializer) throws InterruptedException {
        return open(remoteAddress, null, 10000, autoRead, group, initializer);
    }

    public static ChannelFuture open(final SocketAddress remoteAddress, final AddressResolverGroup<SocketAddress> resolver,
                                     final boolean autoRead, final EventLoopGroup group, final ChannelHandler initializer) {
      return open(remoteAddress, resolver, 10000, autoRead, group, initializer);
    }

    public static ChannelFuture open(final SocketAddress remoteAddress, final AddressResolverGroup<SocketAddress> resolver,
                                     final int connectTimeoutMillis, final boolean autoRead, final EventLoopGroup group, final ChannelHandler initializer) {
        final Bootstrap b = new Bootstrap();
        b.option(ChannelOption.AUTO_READ, autoRead);
        b.option(ChannelOption.TCP_NODELAY, true);
        b.option(ChannelOption.SO_KEEPALIVE, true);
        b.option(ChannelOption.CONNECT_TIMEOUT_MILLIS, connectTimeoutMillis);
        b.option(ChannelOption.SO_RCVBUF, 32 * 1024);// 读缓冲区为32k
        if (null != resolver) {
            b.resolver(resolver);
        }
        b.group(group).channel(NioSocketChannel.class).handler(initializer);
        return b.connect(remoteAddress);
    }

    private static SocketAddress createSocketAddress(final String hostname, final int port) {
        return null == hostname ? new InetSocketAddress(port) : new InetSocketAddress(hostname, port);
    }

    /**
     * Create an ssl context.
     *
     * @return ssl context
     */
    public static SslContext createServerSslContext() throws SSLException, CertificateException {
        final SelfSignedCertificate ssc = new SelfSignedCertificate();
        return SslContextBuilder.forServer(ssc.certificate(), ssc.privateKey()).build();
    }

    public static SslContext createClientSslContext() throws SSLException {
        return SslContextBuilder.forClient().trustManager(InsecureTrustManagerFactory.INSTANCE).build();
    }

    public static void shutdownGroupOnClose(final Channel channel, final EventLoopGroup eventLoopGroup) {
        channel.closeFuture().addListener(new ChannelFutureListener() {
            @Override
            public void operationComplete(final ChannelFuture future) {
                eventLoopGroup.shutdownGracefully();
            }
        });
    }

}

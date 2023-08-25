package com.github.pangolin.proxy.server.socks.v5;

import com.github.pangolin.proxy.server.NettyServer;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.socket.SocketChannel;
import lombok.extern.slf4j.Slf4j;

import javax.net.ssl.SSLException;
import java.net.InetSocketAddress;
import java.security.cert.CertificateException;
import java.util.concurrent.ExecutionException;

@Slf4j
public class Socks5ProxyServer extends NettyServer {

    public Socks5ProxyServer(final int listenPort) {
        this(null, listenPort);
    }

    public Socks5ProxyServer(final String listenHost, final int listenPort) {
        super(listenHost, listenPort);
    }

    public ChannelFuture start() throws InterruptedException, CertificateException, SSLException {
        return super.start(new ChannelInitializer<SocketChannel>() {
            @Override
            protected void initChannel(final SocketChannel ch) throws Exception {
                ch.pipeline().addLast(new Socks5ProxyServerHandler(workerGroup));
            }
        });
    }

    public static void main(String[] args) throws InterruptedException, SSLException, CertificateException, ExecutionException {
        new Socks5ProxyServer(1080).start().addListener(new ChannelFutureListener() {
            @Override
            public void operationComplete(final ChannelFuture future) throws Exception {
                if (future.isSuccess()) {
                    final InetSocketAddress localAddress = (InetSocketAddress) future.channel().localAddress();
                    System.out.println(String.format("Server started on %s:%s", localAddress.getHostString(), localAddress.getPort()));
                } else {
                    future.cause().printStackTrace();
                }
            }
        }).sync().channel().closeFuture().sync().await();
    }
}
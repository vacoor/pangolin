package com.github.pangolin.routing.server;

import com.github.pangolin.routing.handler.internal.server.Socks5DatagramServerHandler;
import com.github.pangolin.routing.handler.internal.server.Socks5ProxyServerHandler;
import com.github.pangolin.server.NettyServer;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.DatagramChannel;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioDatagramChannel;

import javax.net.ssl.SSLException;
import java.security.cert.CertificateException;

public class SocksServerTest {
    public static void main(String[] args) throws InterruptedException, SSLException, CertificateException {
        Socks5DatagramServerHandler h = new Socks5DatagramServerHandler();
        final Bootstrap udpBootstrap = new Bootstrap();
        udpBootstrap.group(new NioEventLoopGroup());
        udpBootstrap.channel(NioDatagramChannel.class);
        udpBootstrap.option(ChannelOption.SO_BROADCAST, false);
        udpBootstrap.handler(new ChannelInitializer<DatagramChannel>() {
            @Override
            protected void initChannel(final DatagramChannel ch) throws Exception {
                ch.pipeline().addLast(h);
            }
        });
        udpBootstrap.bind(1080);

        NettyServer server = new NettyServer( 1080);
        server.start(true, new ChannelInitializer<SocketChannel>() {
            @Override
            protected void initChannel(final SocketChannel ch) throws Exception {
                ch.pipeline().addLast(new Socks5ProxyServerHandler(() -> h));
            }
        }).channel().closeFuture().sync();

    }
}
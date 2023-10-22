package com.github.pangolin.routing.server;

import com.github.pangolin.routing.internal.server.socks.Socks5ProxyServerHandler2;
import com.github.pangolin.server.NettyServer;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.socket.SocketChannel;

import javax.net.ssl.SSLException;
import java.security.cert.CertificateException;

public class SocksServerTest {
    public static void main(String[] args) throws InterruptedException, SSLException, CertificateException {
        NettyServer server = new NettyServer(1080);
        server.start(true, new ChannelInitializer<SocketChannel>() {
            @Override
            protected void initChannel(final SocketChannel ch) throws Exception {
                ch.pipeline().addLast(new Socks5ProxyServerHandler2());
            }
        }).channel().closeFuture().sync();
    }
}
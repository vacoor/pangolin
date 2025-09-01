package com.github.pangolin.routing.server;

import com.github.pangolin.routing.support.StandardSocketChannelFactory;
import com.github.pangolin.server.NettyServer;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.socket.SocketChannel;

import javax.net.ssl.SSLException;
import java.security.cert.CertificateException;

public class SniProxyServerTest {
    public static void main(String[] args) throws InterruptedException, SSLException, CertificateException {
        final StandardSocketChannelFactory factory = new StandardSocketChannelFactory(null);
        NettyServer server = new NettyServer(443);
        server.start(true, new ChannelInitializer<SocketChannel>() {
            @Override
            protected void initChannel(final SocketChannel ch) throws Exception {
//                ch.pipeline().addLast(Channels.createServerSslContext().newHandler(ch.alloc()));
                ch.pipeline().addLast(new SniProxyHandler(factory));
            }
        }).channel().closeFuture().sync();
    }
}
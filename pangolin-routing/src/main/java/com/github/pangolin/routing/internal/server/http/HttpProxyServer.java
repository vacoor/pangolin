package com.github.pangolin.routing.internal.server.http;

import com.github.pangolin.server.NettyServer;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.socket.SocketChannel;

import javax.net.ssl.SSLException;
import java.security.cert.CertificateException;

/**
 */
public class HttpProxyServer {

    public static void main(String[] args) throws InterruptedException, SSLException, CertificateException {
        new NettyServer(8088).start(true, new ChannelInitializer<SocketChannel>() {
            @Override
            protected void initChannel(final SocketChannel ch) throws Exception {
                /*
                 curl --routing-insecure -x https://127.0.0.1:8080 "https://www.baidu.com"
                 */
//                final SslContext ssl = Channels.createServerSslContext();
//                ch.pipeline().addLast(ssl.newHandler(ch.alloc()));
                ch.pipeline().addLast(new HttpProxyServerHandler());
            }
        }).sync().channel().closeFuture().sync();
    }
}

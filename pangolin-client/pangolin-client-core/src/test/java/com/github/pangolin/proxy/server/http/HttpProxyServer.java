package com.github.pangolin.proxy.server.http;

import com.github.pangolin.proxy.server.NettyServer;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;

import javax.net.ssl.SSLException;
import java.security.cert.CertificateException;

/**
 * TODO DOC ME!.
 *
 * @author changhe.yang
 * @since 20230829
 */
public class HttpProxyServer {

    public static void main(String[] args) throws InterruptedException, SSLException, CertificateException {
        new NettyServer(8088).start(true, new ChannelInitializer<SocketChannel>() {
            @Override
            protected void initChannel(final SocketChannel ch) throws Exception {
                /*
                 curl --proxy-insecure -x https://127.0.0.1:8080 "https://www.baidu.com"
                 */
//                final SslContext ssl = Channels.createServerSslContext();
//                ch.pipeline().addLast(ssl.newHandler(ch.alloc()));
                ch.pipeline().addLast(new HttpProxyServerHandler(new NioEventLoopGroup()));
            }
        }).sync().channel().closeFuture().sync();
    }
}

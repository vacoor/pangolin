package com.github.pangolin.routing.server;

import com.github.pangolin.handler.TcpInboundRedirectHandler;
import com.github.pangolin.routing.handler.internal.server.Socks5ProxyServerHandler;
import com.github.pangolin.routing.handler.internal.server.support.StandardSocketChannelFactory;
import com.github.pangolin.server.NettyServer;
import com.github.pangolin.util.Channels;
import com.google.common.collect.Maps;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.codec.socksx.v4.Socks4CommandRequest;
import io.netty.handler.ssl.SniHandler;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslHandler;
import io.netty.util.DomainNameMapping;
import io.netty.util.DomainNameMappingBuilder;

import javax.net.ssl.SSLException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.security.cert.CertificateException;

public class HttpsServerTest {
    public static void main(String[] args) throws InterruptedException, SSLException, CertificateException {
        final StandardSocketChannelFactory factory = new StandardSocketChannelFactory();
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
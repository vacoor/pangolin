package com.github.pangolin.routing.internal.server.websocket;

import com.github.pangolin.server.NettyServer;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpServerCodec;
import lombok.extern.slf4j.Slf4j;

import javax.net.ssl.SSLException;
import java.net.InetSocketAddress;
import java.security.cert.CertificateException;

@Slf4j
public class WebSocketProxyServer extends NettyServer {
    private static final int MAX_HTTP_CONTENT_LENGTH = 1024 * 1024 * 8;

    private final boolean isSecure;

    public WebSocketProxyServer(final int listenPort, final boolean isSecure) {
        this(null, listenPort, isSecure);
    }

    public WebSocketProxyServer(final String listenHost, final int listenPort, final boolean isSecure) {
        super(listenHost, listenPort, new NioEventLoopGroup(2), new NioEventLoopGroup(100));
        this.isSecure = isSecure;
    }

    public ChannelFuture start() throws InterruptedException, CertificateException, SSLException {
        return super.start(true, new ChannelInitializer<SocketChannel>() {
            @Override
            protected void initChannel(final SocketChannel ch) throws Exception {
                final ChannelPipeline cp = ch.pipeline();
                if (isSecure) {
                    cp.addLast(createServerSslContext().newHandler(ch.alloc()));
                }
                cp.addLast(new HttpServerCodec(), new HttpObjectAggregator(MAX_HTTP_CONTENT_LENGTH));
                cp.addLast(new WebSocketProxyServerHandler(
                        true, 65536, true
                ));
            }
        });
    }

    public static void main(String[] args) throws Exception {
        new WebSocketProxyServer(2345, false).start().addListener(new ChannelFutureListener() {
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
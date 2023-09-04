package com.github.pangolin.proxy.server.socks.v5;

import com.github.pangolin.server.NettyServer;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.socket.SocketChannel;
import lombok.extern.slf4j.Slf4j;

import javax.net.ssl.SSLException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.security.cert.CertificateException;
import java.util.concurrent.ExecutionException;

@Slf4j
public class Socks5WebSocketProxyServer extends NettyServer {
    private final URI webSocketProxyServerEndpoint;
    private final String webSocketProxyServerProtocol;

    public Socks5WebSocketProxyServer(final int listenPort, final URI webSocketProxyServerEndpoint, final String webSocketProxyServerProtocol) {
        this(null, listenPort, webSocketProxyServerEndpoint, webSocketProxyServerProtocol);
    }

    public Socks5WebSocketProxyServer(final String listenHost, final int listenPort, final URI webSocketProxyServerEndpoint, final String webSocketProxyServerProtocol) {
        super(listenHost, listenPort);
        this.webSocketProxyServerEndpoint = webSocketProxyServerEndpoint;
        this.webSocketProxyServerProtocol = webSocketProxyServerProtocol;
    }

    public ChannelFuture start() throws InterruptedException, CertificateException, SSLException {
        return super.start(true, new ChannelInitializer<SocketChannel>() {
            @Override
            protected void initChannel(final SocketChannel ch) throws Exception {
//                ch.pipeline().addLast(new FlowControlHandler());
                ch.pipeline().addLast(new Socks5WebSocketProxyServerHandler(webSocketProxyServerEndpoint, webSocketProxyServerProtocol));
            }
        });
    }

    public static void main(String[] args) throws InterruptedException, SSLException, CertificateException, ExecutionException {
//        final URI webSocketProxyServerEndpoint = URI.create("ws://192.168.1.201:2345/tunnel");
        final URI webSocketProxyServerEndpoint = URI.create("ws://127.0.0.1:2345/tunnel");
         final String protocol = "CONNECT";
//        final String protocol = "";
        new Socks5WebSocketProxyServer(1080, webSocketProxyServerEndpoint, protocol).start().addListener(new ChannelFutureListener() {
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

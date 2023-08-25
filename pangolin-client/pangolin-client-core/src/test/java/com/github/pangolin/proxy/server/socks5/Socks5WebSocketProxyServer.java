package com.github.pangolin.proxy.server.socks5;

import com.github.pangolin.proxy.NettyServer;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.socket.SocketChannel;
import lombok.extern.slf4j.Slf4j;

import javax.net.ssl.SSLException;
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

    public Channel start() throws InterruptedException, CertificateException, SSLException {
        return super.start(new ChannelInitializer<SocketChannel>() {
            @Override
            protected void initChannel(final SocketChannel ch) throws Exception {
                ch.pipeline().addLast(new Socks5WebSocketProxyServerHandler(webSocketProxyServerEndpoint, webSocketProxyServerProtocol, workerGroup));
            }
        });
    }

    public static void main(String[] args) throws InterruptedException, SSLException, CertificateException, ExecutionException {
        final URI webSocketProxyServerEndpoint = URI.create("ws://127.0.0.1:8888/ws/echo");
        new Socks5WebSocketProxyServer(1008, webSocketProxyServerEndpoint, null).start().closeFuture().sync().await();
    }
}

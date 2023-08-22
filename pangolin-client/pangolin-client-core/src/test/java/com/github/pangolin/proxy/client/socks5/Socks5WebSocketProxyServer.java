package com.github.pangolin.proxy.client.socks5;

import com.github.pangolin.proxy.NettyServer;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.socket.SocketChannel;
import lombok.extern.slf4j.Slf4j;

import javax.net.ssl.SSLException;
import java.net.URI;
import java.security.cert.CertificateException;
import java.util.concurrent.ExecutionException;

/**
 * WebSocket 隧道服务.
 *
 * @author changhe.yang
 * @since 20210825
 */
@Slf4j
public class Socks5WebSocketProxyServer extends NettyServer {
    private final URI webSocketProxyServerEndpoint;
    private final String webSocketProxyServerProtocol;

    /**
     * 创建隧道服务实例.
     *
     * @param listenPort 监听端口
     */
    public Socks5WebSocketProxyServer(final int listenPort, final URI webSocketProxyServerEndpoint, final String webSocketProxyServerProtocol) {
        this(null, listenPort, webSocketProxyServerEndpoint, webSocketProxyServerProtocol);
    }

    /**
     * 创建隧道服务实例.
     *
     * @param listenHost 监听地址
     * @param listenPort 监听端口
     */
    public Socks5WebSocketProxyServer(final String listenHost, final int listenPort, final URI webSocketProxyServerEndpoint, final String webSocketProxyServerProtocol) {
        super(listenHost, listenPort);
        this.webSocketProxyServerEndpoint = webSocketProxyServerEndpoint;
        this.webSocketProxyServerProtocol = webSocketProxyServerProtocol;
    }

    /**
     * 启动服务.
     *
     * @return 服务通道
     */
    public Channel start() throws InterruptedException, CertificateException, SSLException {
        return super.start(new ChannelInitializer<SocketChannel>() {
            @Override
            protected void initChannel(final SocketChannel ch) throws Exception {
                ch.pipeline().addLast(new Socks5WebSocketProxyServerHandler(webSocketProxyServerEndpoint, webSocketProxyServerProtocol, bossGroup));
            }
        });
    }

    public static void main(String[] args) throws InterruptedException, SSLException, CertificateException, ExecutionException {
        final int listenPort = 1008;
        final URI webSocketProxyServerEndpoint = URI.create("wss://127.0.0.1:8888/ws");
        final String webSocketProxyServerProtocol = null;
        final Socks5WebSocketProxyServer server = new Socks5WebSocketProxyServer(listenPort, webSocketProxyServerEndpoint, webSocketProxyServerProtocol);
        final Channel channel = server.start();
        channel.closeFuture().sync().get();
    }
}

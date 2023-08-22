package com.github.pangolin.proxy.server;

import com.github.pangolin.proxy.NettyServer;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpServerCodec;
import lombok.extern.slf4j.Slf4j;

import javax.net.ssl.SSLException;
import java.security.cert.CertificateException;
import java.util.concurrent.ExecutionException;

@Slf4j
public class WebSocketProxyServer extends NettyServer {
    private static final int MAX_HTTP_CONTENT_LENGTH = 1024 * 1024 * 8;

    private final boolean isSecure;

    /**
     * 创建隧道服务实例.
     *
     * @param listenPort 监听端口
     */
    public WebSocketProxyServer(final int listenPort, final boolean isSecure) {
        this(null, listenPort, isSecure);
    }

    /**
     * 创建隧道服务实例.
     *
     * @param listenHost 监听地址
     * @param listenPort 监听端口
     */
    public WebSocketProxyServer(final String listenHost, final int listenPort, final boolean isSecure) {
        super(listenHost, listenPort, new NioEventLoopGroup(2), new NioEventLoopGroup(100));
        this.isSecure = isSecure;
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
                final ChannelPipeline cp = ch.pipeline();
                if (isSecure) {
                    cp.addLast(createServerSslContext().newHandler(ch.alloc()));
                }
                cp.addLast(new HttpServerCodec(), new HttpObjectAggregator(MAX_HTTP_CONTENT_LENGTH));
                cp.addLast(new WebSocketProxyServerHandler(workersGroup, "/ws", "*", false, 65536, true, true));
            }
        });
    }

    public static void main(String[] args) throws InterruptedException, SSLException, CertificateException, ExecutionException {
        final WebSocketProxyServer server = new WebSocketProxyServer(8888, true);
        final Channel channel = server.start();
        channel.closeFuture().sync().get();
    }
}
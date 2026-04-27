package com.github.pangolin.server;

import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolHandler;
import io.netty.handler.ssl.SslContext;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import javax.net.ssl.SSLException;
import java.security.cert.CertificateException;

/**
 *
 */
@Slf4j
public class WebSocketBridgeServer extends NettyServer {
    /**
     * 最大 HTTP 内容长度.
     */
    private static final int MAX_HTTP_CONTENT_LENGTH = 1024 * 1024 * 8;

    /**
     * 是否使用 SSL.
     */
    private final boolean useSsl;

    /**
     * 接入点路径.
     */
    private final String endpointPath;

    private final WebSocketBridgeServerEngine webSocketBridgeServerEngine;
    @Getter
    private final WebSocketBridgeServerForwarder webSocketBridgeServerForwarder;


    private Channel boundChannel;

    /**
     * 创建隧道服务实例.
     *
     * @param listenPort   监听端口
     * @param endpointPath 接入点路径
     * @param useSsl       是否使用 SSL
     */
    public WebSocketBridgeServer(final int listenPort,
                                 final String endpointPath, final boolean useSsl) {
        this(null, listenPort, endpointPath, useSsl);
    }

    /**
     * 创建隧道服务实例.
     *
     * @param listenHost   监听地址
     * @param listenPort   监听端口
     * @param endpointPath 接入点路径
     * @param useSsl       是否使用 SSL
     */
    public WebSocketBridgeServer(final String listenHost, final int listenPort,
                                 final String endpointPath, final boolean useSsl) {
        super(listenHost, listenPort);
        this.endpointPath = endpointPath;
        this.useSsl = useSsl;
        this.webSocketBridgeServerEngine = new WebSocketBridgeServerEngine();
        this.webSocketBridgeServerForwarder = new WebSocketBridgeServerForwarder(webSocketBridgeServerEngine, bossGroup, workerGroup);
    }

    /**
     * 启动服务.
     *
     * @return 服务通道
     */
    public Channel start() throws InterruptedException, CertificateException, SSLException {
        final SslContext sslContext = useSsl ? createServerSslContext() : null;
        return super.start(true, new ChannelInitializer<SocketChannel>() {
            @Override
            protected void initChannel(final SocketChannel ch) throws Exception {
                final ChannelPipeline pipeline = ch.pipeline();
                if (null != sslContext) {
                    pipeline.addLast(sslContext.newHandler(ch.alloc()));
                }
                pipeline.addLast(
                        new HttpServerCodec(),
                        new HttpObjectAggregator(MAX_HTTP_CONTENT_LENGTH),
                        /*- 浏览器似乎处理压缩有问题(permessage-deflate).
                        new WebSocketServerCompressionHandler(),
                        new WebSocketServerProtocolHandler(endpointPath, ALL_PROTOCOLS, true, 65536, true, true),
                        */
                        new WebSocketServerProtocolHandler(endpointPath, "*", false, 65536, true, true),
                        new WebSocketBridgeServerHandler(endpointPath, webSocketBridgeServerEngine, webSocketBridgeServerForwarder)
                );
            }
        }).sync().channel();
    }

    public static void main(String[] args) throws Exception {
        final boolean useSsl = !Boolean.getBoolean("websocket.bridge.no-ssl");
        final WebSocketBridgeServer server = new WebSocketBridgeServer(2345, "/tunnel", useSsl);

        final Channel channel = server.start();
        log.info("WebSocket bridge server started on {}", channel.localAddress());

        channel.closeFuture().sync();
    }
}

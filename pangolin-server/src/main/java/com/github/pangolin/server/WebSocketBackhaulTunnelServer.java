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
public class WebSocketBackhaulTunnelServer extends NettyServer {
    /**
     * 最大 HTTP 内容长度.
     */
    private static final int MAX_HTTP_CONTENT_LENGTH = 1024 * 1024 * 8;

    /**
     * 默认通道响应超时时间.
     */
    private static final long DEFAULT_BACKHAUL_LINK_TIMEOUT_MS = 20 * 1000L;

    /**
     * 回程链路超时时间.
     */
    private final long backhaulTimeoutMs = DEFAULT_BACKHAUL_LINK_TIMEOUT_MS;

    /**
     * 是否使用 SSL.
     */
    private final boolean useSsl;

    /**
     * 接入点路径.
     */
    private final String endpointPath;

    private final WebSocketBackhaulTunnelServerEngine webSocketBackhaulTunnelServerEngine;
    @Getter
    private final WebSocketBackhaulTunnelServerForwarder webSocketBackhaulTunnelServerForwarder;


    private Channel boundChannel;

    /**
     * 创建隧道服务实例.
     *
     * @param listenPort   监听端口
     * @param endpointPath 接入点路径
     * @param useSsl       是否使用 SSL
     */
    public WebSocketBackhaulTunnelServer(final int listenPort, final String endpointPath, final boolean useSsl) {
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
    public WebSocketBackhaulTunnelServer(final String listenHost, final int listenPort, final String endpointPath, final boolean useSsl) {
        super(listenHost, listenPort);
        this.endpointPath = endpointPath;
        this.useSsl = useSsl;
        this.webSocketBackhaulTunnelServerEngine = new WebSocketBackhaulTunnelServerEngine();
        this.webSocketBackhaulTunnelServerForwarder = new WebSocketBackhaulTunnelServerForwarder(webSocketBackhaulTunnelServerEngine, new NioEventLoopGroup(2), new NioEventLoopGroup());
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
                        new WebSocketBackhaulTunnelServerHandler(endpointPath, webSocketBackhaulTunnelServerEngine, webSocketBackhaulTunnelServerForwarder)
                );
            }
        }).sync().channel();
    }

    public static void main(String[] args) throws Exception {
        final WebSocketBackhaulTunnelServer server = new WebSocketBackhaulTunnelServer(2345, "/tunnel", false);

        final Channel channel = server.start();
        System.out.println("Start on " + channel.localAddress());

        /*
        server.webSocketBackhaulTunnelServerForwarder.addForwarding(
                3389, "BZ",
                InetSocketAddress.createUnresolved("127.0.0.1", 3389)
        );
        */

        /*
        final Terminal terminal = TerminalFactory.create();
        final ConsoleReader console = ConsoleReaderFactory.newConsoleReader(System.in, System.out, terminal, () -> server.webSocketBackhaulTunnelServerEngine.getAgents().stream().map(WebSocketBackhaulTunnelServerEngine.Agent::getId).collect(Collectors.toSet()));
        WebSocketBackhaulTunnelServerShell.create(console, true, server.webSocketBackhaulTunnelServerEngine, server.webSocketBackhaulTunnelServerForwarder).start();
        */

        channel.closeFuture().sync();
    }
}

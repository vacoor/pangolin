package com.github.pangolin.server.v11;

import com.github.pangolin.util.Channels;
import com.github.pangolin.util.Redirects;
import com.github.pangolin.util.Util;
import com.github.pangolin.util.WebSocketUtils;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.QueryStringDecoder;
import io.netty.handler.codec.http.websocketx.WebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolHandler;
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolHandler.HandshakeComplete;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.timeout.IdleStateEvent;
import io.netty.util.concurrent.DefaultThreadFactory;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.FutureListener;
import io.netty.util.concurrent.Promise;
import lombok.extern.slf4j.Slf4j;

import javax.net.ssl.SSLException;
import java.net.URI;
import java.security.cert.CertificateException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 *
 */
@Slf4j
public class Server {
    /**
     * 空字符串.
     */
    private static final String EMPTY = "";

    /**
     * 最大 HTTP 内容长度.
     */
    private static final int MAX_HTTP_CONTENT_LENGTH = 1024 * 1024 * 8;

    /**
     * 默认通道响应超时时间.
     */
    private static final long DEFAULT_BACKHAUL_LINK_TIMEOUT_MS = 20 * 1000L;

    /**
     * 通道Agent注册.
     */
    private static final String PROTOCOL_AGENT_REGISTER = "AGENT-REGISTER";

    /**
     * 通道请求.
     */
    private static final String PROTOCOL_TUNNEL_REQUEST = "";

    /**
     * 通道打开.
     */
    private static final String PROTOCOL_TUNNEL_RESPONSE = "TUNNEL_RESPONSE";

    /**
     * 服务管理.
     */
    private static final String PROTOCOL_TUNNEL_MANAGEMENT = "TUNNEL-MGR";

    /**
     * 支持的协议.
     */
    private static final String ALL_PROTOCOLS = PROTOCOL_TUNNEL_REQUEST + "," + PROTOCOL_AGENT_REGISTER + "," + PROTOCOL_TUNNEL_RESPONSE + "," + PROTOCOL_TUNNEL_MANAGEMENT;


    /**
     * 端口转发的服务连接(tunnel:server-channel).
     */
//    private final ConcurrentMap<String, List<Channel>> tcpListenChannelMap = new ConcurrentHashMap<>();


    /**
     * 已开启的端口转发监听.
     */
//    private final ConcurrentMap<Integer, PortForwarding> tcpForwardRuleMap = new ConcurrentHashMap<>();

    /**
     * 服务 event loop group.
     */
    private final NioEventLoopGroup bossGroup = new NioEventLoopGroup(1, new DefaultThreadFactory("WebSocketBackhaulProxyServer-boss", true));

    /**
     * 处理 event loop group.
     */
    private final NioEventLoopGroup workerGroup = new NioEventLoopGroup(0, new DefaultThreadFactory("WebSocketBackhaulProxyServer-workers", true));

    /**
     *
     */
    private final AtomicBoolean startup = new AtomicBoolean(false);

    /**
     * 回程链路超时时间.
     */
    private final long backhaulTimeoutMs = DEFAULT_BACKHAUL_LINK_TIMEOUT_MS;

    /**
     * 监听端口.
     */
    private final int listenPort;

    /**
     * 监听主机名.
     */
    private final String listenHost;

    /**
     * 是否使用 SSL.
     */
    private final boolean useSsl;

    /**
     * 接入点路径.
     */
    private final String endpointPath;


    private Channel boundChannel;

    private Discover discover;

    /**
     * 创建隧道服务实例.
     *
     * @param listenPort   监听端口
     * @param endpointPath 接入点路径
     * @param useSsl       是否使用 SSL
     */
    public Server(final int listenPort, final String endpointPath, final boolean useSsl) {
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
    public Server(final String listenHost, final int listenPort, final String endpointPath, final boolean useSsl) {
        this.listenHost = listenHost;
        this.listenPort = listenPort;
        this.endpointPath = endpointPath;
        this.useSsl = useSsl;
        this.discover = new Discover();
    }

    /**
     * 启动服务.
     *
     * @return 服务通道
     */
    public Channel start() throws InterruptedException, CertificateException, SSLException {
        if (!startup.compareAndSet(false, true)) {
            return boundChannel;
        }

        final SslContext sslContext = useSsl ? createSslContext() : null;
        return boundChannel = Channels.listen(listenHost, listenPort, bossGroup, workerGroup, new ChannelInitializer<SocketChannel>() {
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
                        new WebSocketServerProtocolHandler(endpointPath, ALL_PROTOCOLS, false, 65536, true, true),
                        // new IdleStateHandler(0, 0, 60, TimeUnit.SECONDS),
                        createWebSocketTunnelServerHandler()
                );
            }
        }).sync().channel();
    }

    /**
     * Create an ssl context.
     *
     * @return ssl context
     */
    private SslContext createSslContext() throws SSLException, CertificateException {
        return Channels.createServerSslContext();
    }

    /**
     * 创建通道服务处理器.
     *
     * @return 通道服务处理器
     */
    private SimpleChannelInboundHandler<WebSocketFrame> createWebSocketTunnelServerHandler() {
        return new SimpleChannelInboundHandler<WebSocketFrame>() {
            @Override
            public void userEventTriggered(final ChannelHandlerContext webSocketContext, final Object evt) throws Exception {
                if (evt instanceof IdleStateEvent) {
                    webSocketContext.close();
                    return;
                }
                if (!(evt instanceof HandshakeComplete)) {
                    super.userEventTriggered(webSocketContext, evt);
                    return;
                }
                final HandshakeComplete handshake = (HandshakeComplete) evt;
                final String subprotocol = null != handshake.selectedSubprotocol() ? handshake.selectedSubprotocol() : EMPTY;
                if (PROTOCOL_AGENT_REGISTER.equalsIgnoreCase(subprotocol)) {
                    /*-
                     * 中继节点注册.
                     */
                    discover.agentRegistered(handshake, webSocketContext);
                } else if (PROTOCOL_TUNNEL_REQUEST.equalsIgnoreCase(subprotocol)) {
                    /*-
                     * 隧道打开请求.
                     */
                    webSocketTunnelRequested(webSocketContext, handshake);
                } else if (PROTOCOL_TUNNEL_RESPONSE.equalsIgnoreCase(subprotocol)) {
                    /*-
                     * 隧道回传打开响应.
                     */
                    discover.tunnelResponded(handshake, webSocketContext);
                } else if (PROTOCOL_TUNNEL_MANAGEMENT.equalsIgnoreCase(subprotocol)) {
//                    tunnelManagement(webSocketContext, handshake);
                } else {
                    WebSocketUtils.protocolErrorClose(webSocketContext, "PROTOCOL_NOT_SUPPORTED");
                }
                webSocketContext.fireUserEventTriggered(evt);
            }

            @Override
            protected void channelRead0(final ChannelHandlerContext webSocketContext, final WebSocketFrame msg) throws Exception {
                /*-
                 * Only tunnel bus arrived.
                 */
                log.warn("no handler found for message: {}", msg);
            }

            @Override
            public void exceptionCaught(final ChannelHandlerContext webSocketContext, final Throwable cause) throws Exception {
                // FIXME
                super.exceptionCaught(webSocketContext, cause);
            }
        };
    }

    private void webSocketTunnelRequested(final ChannelHandlerContext accessCtx, final HandshakeComplete handshake) throws InterruptedException {
        final QueryStringDecoder decoder = new QueryStringDecoder(handshake.requestUri());
        final Map<String, List<String>> parameters = decoder.parameters();
        final String tunnel = Util.last(parameters, "tunnel");
        final String target = Util.last(parameters, "target");

        final String id = "ws:" + id(accessCtx.channel());

        if (log.isDebugEnabled()) {
            log.debug("{} Try open websocket tunnel: {}", accessCtx.channel(), target);
        }

        accessCtx.channel().config().setAutoRead(false);
        Promise<ChannelHandlerContext> backhaulPromise = discover.tunnelRequested(id, tunnel, URI.create(target), accessCtx);
        backhaulPromise.addListener(new FutureListener<ChannelHandlerContext>() {
            @Override
            public void operationComplete(final Future<ChannelHandlerContext> backhaulFuture) {
                if (backhaulFuture.isSuccess()) {
                    final ChannelHandlerContext backhaulCtx = backhaulFuture.getNow();
                    backhaulCtx.channel().config().setAutoRead(false);

                    accessCtx.pipeline().replace(accessCtx.name(), null, Redirects.webSocketRedirectToWebSocket(backhaulCtx));
                    backhaulCtx.pipeline().replace(backhaulCtx.name(), null, Redirects.webSocketRedirectToWebSocket(accessCtx));

                    accessCtx.channel().config().setAutoRead(true);
                    backhaulCtx.channel().config().setAutoRead(true);

                    if (log.isDebugEnabled()) {
                        log.debug("{} WebSocket tunnel open success: {}", accessCtx.channel(), backhaulCtx.channel());
                    }
                } else {
                    final Throwable cause = backhaulFuture.cause();
                    log.error("{} WebSocket tunnel open failure: {}", accessCtx.channel(), cause.getMessage());
                    WebSocketUtils.goingAwayClose(accessCtx, cause.getMessage());
                }
            }
        });
    }

    private String id(final Channel channel) {
        return "0x" + channel.id().asShortText();
    }

    /**
     * 强制关闭隧道.
     *
     * @param linkId 隧道ID
     * @return 如果隧道存在返回true, 否则false
     */
    public boolean kill(final String linkId) throws InterruptedException {
        return discover.kill(linkId);
    }

    /**
     * 关闭服务器.
     */
    public void shutdownGracefully() {
        if (null != boundChannel) {
            boundChannel.close();
        }
        bossGroup.shutdownGracefully();
        workerGroup.shutdownGracefully();
    }

}

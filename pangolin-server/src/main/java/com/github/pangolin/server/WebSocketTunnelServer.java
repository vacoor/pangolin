package com.github.pangolin.server;

import com.github.pangolin.server.shell.ConsoleLineReader;
import com.github.pangolin.server.shell.LineReader;
import com.github.pangolin.server.shell.WebSocketTerminal;
import com.github.pangolin.server.shell.WebSocketTunnelShell;
import com.github.pangolin.util.WebSocketForwarder;
import com.github.pangolin.util.WebSocketUtils;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.websocketx.BinaryWebSocketFrame;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolHandler;
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolHandler.HandshakeComplete;
import io.netty.handler.codec.http.websocketx.extensions.compression.WebSocketServerCompressionHandler;
import io.netty.handler.logging.LogLevel;
import io.netty.handler.logging.LoggingHandler;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.util.SelfSignedCertificate;
import io.netty.handler.timeout.IdleStateEvent;
import io.netty.handler.timeout.IdleStateHandler;
import io.netty.util.concurrent.*;
import jline.Terminal;
import lombok.extern.slf4j.Slf4j;

import javax.net.ssl.SSLException;
import java.io.*;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.URI;
import java.security.cert.CertificateException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * WebSocket 隧道服务.
 *
 * @author changhe.yang
 * @since 20210825
 */
@Slf4j
public class WebSocketTunnelServer {
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
     * 通道注册.
     */
    private static final String PROTOCOL_NODE_REGISTER = "PASSIVE-REG";

    /**
     * 通道请求.
     */
    private static final String PROTOCOL_TUNNEL_REQUEST = "";

    /**
     * 通道打开.
     */
    private static final String PROTOCOL_TUNNEL_RESPONSE = "PASSIVE";

    /**
     * 服务管理.
     */
    private static final String PROTOCOL_TUNNEL_MANAGEMENT = "TUNNEL-MGR";

    /**
     * 支持的协议.
     */
    private static final String ALL_PROTOCOLS = PROTOCOL_TUNNEL_REQUEST + "," + PROTOCOL_NODE_REGISTER + "," + PROTOCOL_TUNNEL_RESPONSE + "," + PROTOCOL_TUNNEL_MANAGEMENT;

    /**
     * 注册的broker节点(tunnel-id:node-channel).
     */
    private final ConcurrentMap<String, Broker> brokerRegistry = new ConcurrentHashMap<>();

    /**
     * 隧道连接信息(id:tunnel-link).
     */
    private final ConcurrentMap<String, TunnelLink> tunnelLinkMap = new ConcurrentHashMap<>();

    /**
     * 端口转发的服务连接(tunnel:server-channel).
     */
    private final ConcurrentMap<String, List<Channel>> tcpListenChannelMap = new ConcurrentHashMap<>();

    /**
     * 已开启的端口转发监听.
     */
    private final ConcurrentMap<Integer, BrokerForwarding> tcpForwardRuleMap = new ConcurrentHashMap<>();

    /**
     * 服务 event loop group.
     */
    private final NioEventLoopGroup bossGroup = new NioEventLoopGroup(1, new DefaultThreadFactory("WebSocketTunnelServer-boss", true));

    /**
     * 处理 event loop group.
     */
    private final NioEventLoopGroup workerGroup = new NioEventLoopGroup(0, new DefaultThreadFactory("WebSocketTunnelServer-workers", true));

    /**
     * 回程链路超时时间.
     */
    private final long backhaulLinkTimeoutMs = DEFAULT_BACKHAUL_LINK_TIMEOUT_MS;

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

    /**
     *
     */
    private final AtomicBoolean startup = new AtomicBoolean(false);


    private Channel primaryServerChannel;

    /**
     * 创建隧道服务实例.
     *
     * @param listenPort   监听端口
     * @param endpointPath 接入点路径
     * @param useSsl       是否使用 SSL
     */
    public WebSocketTunnelServer(final int listenPort, final String endpointPath, final boolean useSsl) {
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
    public WebSocketTunnelServer(final String listenHost, final int listenPort, final String endpointPath, final boolean useSsl) {
        this.listenHost = listenHost;
        this.listenPort = listenPort;
        this.endpointPath = endpointPath;
        this.useSsl = useSsl;
    }

    /**
     * 启动服务.
     *
     * @return 服务通道
     */
    public Channel start() throws CertificateException, SSLException, InterruptedException {
        if (!startup.compareAndSet(false, true)) {
            return primaryServerChannel;
        }

        final SslContext sslContext = useSsl ? createSslContext() : null;
        final ServerBootstrap bootstrap = new ServerBootstrap();
        bootstrap.option(ChannelOption.SO_REUSEADDR, true);
        bootstrap.childOption(ChannelOption.TCP_NODELAY, true);
        bootstrap.group(bossGroup, workerGroup)
                .channel(NioServerSocketChannel.class)
                .handler(new LoggingHandler(LogLevel.INFO))
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(final SocketChannel ch) {
                        final ChannelPipeline pipeline = ch.pipeline();
                        if (null != sslContext) {
                            pipeline.addLast(sslContext.newHandler(ch.alloc()));
                        }
                        pipeline.addLast(
                                new HttpServerCodec(),
                                new HttpObjectAggregator(MAX_HTTP_CONTENT_LENGTH),
                                new WebSocketServerCompressionHandler(),
                                new WebSocketServerProtocolHandler(endpointPath, ALL_PROTOCOLS, true, MAX_HTTP_CONTENT_LENGTH, false, true),
                                new IdleStateHandler(0, 0, 60, TimeUnit.SECONDS),
                                createWebSocketTunnelServerHandler()
                        );
                    }
                });

        if (null == listenHost) {
            primaryServerChannel = bootstrap.bind(listenPort).sync().channel();
        } else {
            primaryServerChannel = bootstrap.bind(listenHost, listenPort).sync().channel();
        }
        return primaryServerChannel;
    }

    public void expiredCheck() {
        for (Map.Entry<String, Broker> entry : brokerRegistry.entrySet()) {
            if (!entry.getValue().bus.channel().isActive()) {
                log.warn("Expired: {}", entry.getValue());
                brokerRegistry.remove(entry.getKey());
            }
        }
    }

    /**
     * Create an ssl context.
     *
     * @return ssl context
     */
    private SslContext createSslContext() throws SSLException, CertificateException {
        final SelfSignedCertificate ssc = new SelfSignedCertificate();
        return SslContextBuilder.forServer(ssc.certificate(), ssc.privateKey()).build();
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
                if (PROTOCOL_NODE_REGISTER.equalsIgnoreCase(subprotocol)) {
                    /*-
                     * 中继节点注册.
                     */
                    nodeRegistered(webSocketContext, handshake);
                } else if (PROTOCOL_TUNNEL_REQUEST.equalsIgnoreCase(subprotocol)) {
                    /*-
                     * 隧道打开请求.
                     */
                    final Map<String, String> parameters = determineQueryParameters(handshake.requestUri());
                    final String tunnel = parameters.get("tunnel");
                    final String target = parameters.get("target");
                    final ChannelHandlerContext tunnelBus = lookupBroker(tunnel).bus;
                    if (null != tunnelBus) {
                        final String forwardRequestId = "ws:" + id(webSocketContext.channel());
                        final Promise<ChannelHandlerContext> webSocketTunnelPromise = webSocketTunnelRequested(tunnel, forwardRequestId, webSocketContext);
                        final String forwardRequest = forwardRequestId + "->" + target;

                        if (log.isDebugEnabled()) {
                            log.debug("{} Try open websocket tunnel: {}", webSocketContext.channel(), forwardRequest);
                        }
                        tunnelBus.writeAndFlush(new TextWebSocketFrame(forwardRequest));
                        if (!webSocketTunnelPromise.await(backhaulLinkTimeoutMs, TimeUnit.MILLISECONDS)) {
                            webSocketTunnelPromise.tryFailure(new ConnectTimeoutException("TUNNEL_WAIT_TIMOUT"));
                        }
                    } else {
                        log.warn("{} Not found tunnel: {}, will close", webSocketContext.channel(), tunnel);
                        WebSocketUtils.policyViolationClose(webSocketContext, "TUNNEL_NOT_FOUND");
                    }
                } else if (PROTOCOL_TUNNEL_RESPONSE.equalsIgnoreCase(subprotocol)) {
                    /*-
                     * 隧道回传打开响应.
                     */
                    tunnelResponded(webSocketContext, handshake);
                } else if (PROTOCOL_TUNNEL_MANAGEMENT.equalsIgnoreCase(subprotocol)) {
                    tunnelManagement(webSocketContext, handshake);
                } else {
                    WebSocketUtils.protocolErrorClose(webSocketContext, "PROTOCOL_NOT_SUPPORTED");
                }
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

    private Map<String, String> determineQueryParameters(final String uri) {
        final String rawQuery = URI.create(uri).getQuery();
        return null != rawQuery ? splitQuery(rawQuery) : Collections.<String, String>emptyMap();
    }

    /**
     * Splits query string to map.
     *
     * @param query the query string
     * @return the name-value pairs
     */
    private static Map<String, String> splitQuery(final String query) {
        final Map<String, String> result = new HashMap<String, String>(15);
        final String[] pairs = query.split("&");
        if (pairs.length > 0) {
            for (final String pair : pairs) {
                final String[] param = pair.split("=", 2);
                if (param.length == 2) {
                    result.put(param[0], param[1]);
                }
            }
        }
        return result;
    }

    /*- *******************************
     *
     *
     * ********************************/

    /**
     * 查找通信链接.
     *
     * @param nodeKey 节点标识
     * @return 通信总线
     */
    public Broker lookupBroker(final String nodeKey) {
        return brokerRegistry.get(nodeKey);
    }

    /* ***************** 节点注册 [[ **************** */

    /**
     * 节点注册.
     *
     * @param webSocketContext 节点注册上下文
     * @param handshake        节点注册握手信息
     */
    private void nodeRegistered(final ChannelHandlerContext webSocketContext, final HandshakeComplete handshake) {
        final HttpHeaders headers = handshake.requestHeaders();
        final String nodeName = headers.getAsString("X-Node-Name");
        final String nodeVersion = headers.getAsString("X-Node-Version");
        final String nodeIntranet = headers.getAsString("X-Node-Intranet");

        final SocketAddress address = webSocketContext.channel().remoteAddress();
        String nodeExtranet = address.toString();
        if (address instanceof InetSocketAddress) {
            nodeExtranet = ((InetSocketAddress) address).getAddress().getHostAddress();
        }

        if (null == nodeName || nodeName.isEmpty()) {
            log.warn("{} Node register failure, node name missing, headers: {}", webSocketContext.channel(), headers);
            WebSocketUtils.policyViolationClose(webSocketContext, "ILLEGAL_NODE_REGISTER");
            return;
        }

        // XXX encode as name
        final String nodeId = String.format("%s@%s/%s", nodeName, nodeIntranet, nodeExtranet);
        final Broker node = new Broker(nodeId, nodeName, nodeVersion, nodeExtranet, nodeIntranet, webSocketContext);
        // TODO register by nodeId
        if (null == brokerRegistry.putIfAbsent(nodeName, node)) {
            webSocketContext.channel().closeFuture().addListener(new GenericFutureListener<Future<? super Void>>() {
                @Override
                public void operationComplete(final Future<? super Void> future) {
                    if (log.isDebugEnabled()) {
                        log.debug("{} Node '{}' connection loosed", webSocketContext.channel(), nodeName);
                    }
                    nodeUnregistered(nodeName, node);
                }
            });
            log.info("{} Node '{}' registered, version: {}, address: {}/{}", webSocketContext.channel(), nodeName, nodeVersion, nodeExtranet, nodeIntranet);
        } else {
            log.warn("{} Node register conflict, '{}' already registered, rejected", webSocketContext.channel(), nodeName);
            WebSocketUtils.policyViolationClose(webSocketContext, "NODE_CONFLICT");
        }
    }

    /**
     * 节点取消注册.
     *
     * @param nodeKey 节点标识
     * @param node    节点信息
     */
    private void nodeUnregistered(final String nodeKey, final Broker node) {
        if (brokerRegistry.remove(nodeKey, node)) {
            log.info("{} Node unregistered: {}", node.bus.channel(), node);

            try {
                this.onNodeUnregisteredClose(node.name, node.bus);
            } finally {
                if (node.bus.channel().isOpen()) {
                    WebSocketUtils.normalClose(node.bus, "UNREGISTER");
                }
            }
        } else {
            log.error("{} Node unregister failure: '{}' not found in registry", node.bus.channel(), node);
        }
    }

    /**
     * 节点取消注册关闭.
     *
     * @param nodeKey          节点标识
     * @param webSocketContext 节点的 webSocket 通信通道
     */
    private void onNodeUnregisteredClose(final String nodeKey, final ChannelHandlerContext webSocketContext) {
        /*-
         * 关闭所有对应的监听端口服务.
         */
        final List<Channel> listenChannels = tcpListenChannelMap.remove(nodeKey);
        final List<Channel> listenChannelsToUse = null != listenChannels ? listenChannels : Collections.<Channel>emptyList();
        for (final Channel listenChannel : listenChannelsToUse) {
            listenChannel.close();
        }
        /*-
         * XXX 是否关闭所有对应的隧道.
         */
    }

    /* ***************** ]] 节点注册 **************** */


    /**
     * 请求接入 WebSocket 隧道.
     *
     * @param accessRequestId     接入请求ID
     * @param webSocketAccessLink 接入链路
     * @return 用于设置回传链接的 promise
     */
    private Promise<ChannelHandlerContext> webSocketTunnelRequested(final String nodeKey, final String accessRequestId,
                                                                    final ChannelHandlerContext webSocketAccessLink) {
        if (log.isDebugEnabled()) {
            log.debug("{} WebSocket tunnel access link: {}", webSocketAccessLink.channel(), accessRequestId);
        }

        final Promise<ChannelHandlerContext> webSocketBackhaulLinkPromise = GlobalEventExecutor.INSTANCE.newPromise();
        final TunnelLink tunnelLink = new TunnelLink(accessRequestId, nodeKey, webSocketAccessLink, webSocketBackhaulLinkPromise);
        if (null != tunnelLinkMap.putIfAbsent(accessRequestId, tunnelLink)) {
            throw new IllegalStateException(String.format("%s access link id '%s' is already used", webSocketAccessLink.channel(), accessRequestId));
        }

        webSocketAccessLink.channel().config().setAutoRead(false);
        webSocketBackhaulLinkPromise.addListener(new FutureListener<ChannelHandlerContext>() {
            @Override
            public void operationComplete(final Future<ChannelHandlerContext> backhaulFuture) {
                if (backhaulFuture.isSuccess()) {
                    final ChannelHandlerContext webSocketBackhaulLink = backhaulFuture.getNow();
                    webSocketBackhaulLink.channel().config().setAutoRead(false);

                    webSocketAccessLink.pipeline().remove(webSocketAccessLink.handler());
                    webSocketBackhaulLink.pipeline().remove(webSocketBackhaulLink.handler());

                    webSocketAccessLink.pipeline().addLast(WebSocketForwarder.pipe(webSocketBackhaulLink.channel()));
                    webSocketBackhaulLink.pipeline().addLast(WebSocketForwarder.pipe(webSocketAccessLink.channel()));

                    webSocketAccessLink.channel().config().setAutoRead(true);
                    webSocketBackhaulLink.channel().config().setAutoRead(true);

                    if (log.isDebugEnabled()) {
                        log.debug("{} WebSocket tunnel open success: {}", webSocketAccessLink.channel(), webSocketBackhaulLink.channel());
                    }
                } else {
                    final Throwable cause = backhaulFuture.cause();
                    log.error("{} WebSocket tunnel open failure: {}", webSocketAccessLink.channel(), cause.getMessage());
                    WebSocketUtils.goingAwayClose(webSocketAccessLink, cause.getMessage());
                }
            }
        });

        webSocketAccessLink.channel().closeFuture().addListener(new GenericFutureListener<Future<? super Void>>() {
            @Override
            public void operationComplete(final Future<? super Void> future) {
                if (log.isDebugEnabled()) {
                    log.debug("{} Tunnel closed, access link: {}", webSocketAccessLink.channel(), accessRequestId);
                }

                final ChannelHandlerContext webSocketBackhaulLink = tunnelLink.backhaulLinkPromise.getNow();
                if (null != webSocketBackhaulLink && webSocketBackhaulLink.channel().isOpen()) {
                    // XXX WebSocketUtils.normalClose(webSocketBackhaulLink, "");
                    webSocketBackhaulLink.close();
                }
                tunnelLinkMap.remove(accessRequestId, tunnelLink);
            }
        });

        return webSocketBackhaulLinkPromise;
    }

    /**
     * 请求接入 TCP 通道.
     *
     * @param accessRequestId        隧道请求 ID
     * @param nativeSocketAccessLink 请求接入的原生 socket链接
     * @return 用于设置回传链接的 promise
     */
    private Promise<ChannelHandlerContext> nativeTunnelRequested(final String tunnel, final String accessRequestId, final ChannelHandlerContext nativeSocketAccessLink) {
        if (log.isDebugEnabled()) {
            log.debug("{} Native tunnel request: {}", nativeSocketAccessLink.channel(), accessRequestId);
        }

        final Promise<ChannelHandlerContext> webSocketBackhaulLinkPromise = GlobalEventExecutor.INSTANCE.newPromise();
        final TunnelLink tunnelLink = new TunnelLink(accessRequestId, tunnel, nativeSocketAccessLink, webSocketBackhaulLinkPromise);
        if (null != tunnelLinkMap.putIfAbsent(accessRequestId, tunnelLink)) {
            throw new IllegalStateException(String.format("%s request id '%s' is already used", nativeSocketAccessLink.channel(), accessRequestId));
        }

        nativeSocketAccessLink.channel().config().setAutoRead(false);
        webSocketBackhaulLinkPromise.addListener(new FutureListener<ChannelHandlerContext>() {
            @Override
            public void operationComplete(final Future<ChannelHandlerContext> backhaulFuture) {
                if (backhaulFuture.isSuccess()) {
                    final ChannelHandlerContext webSocketBackhaulLink = backhaulFuture.getNow();
                    webSocketBackhaulLink.channel().config().setAutoRead(false);

                    nativeSocketAccessLink.pipeline().remove(nativeSocketAccessLink.handler());
                    webSocketBackhaulLink.pipeline().remove(webSocketBackhaulLink.handler());

                    nativeSocketAccessLink.pipeline().addLast(WebSocketForwarder.adaptNativeSocketToWebSocket(webSocketBackhaulLink.channel()));
                    webSocketBackhaulLink.pipeline().addLast(WebSocketForwarder.adaptWebSocketToNativeSocket(nativeSocketAccessLink.channel()));

                    nativeSocketAccessLink.channel().config().setAutoRead(true);
                    webSocketBackhaulLink.channel().config().setAutoRead(true);

                    if (log.isDebugEnabled()) {
                        log.debug("{} Native tunnel open success: {}", nativeSocketAccessLink.channel(), webSocketBackhaulLink.channel());
                    }
                } else {
                    final Throwable cause = backhaulFuture.cause();
                    if (log.isDebugEnabled()) {
                        log.debug("{} Native tunnel open failure: {}", nativeSocketAccessLink.channel(), cause.getMessage());
                    }
                    nativeSocketAccessLink.close();
                }
            }
        });
        nativeSocketAccessLink.channel().closeFuture().addListener(new GenericFutureListener<Future<? super Void>>() {
            @Override
            public void operationComplete(final Future<? super Void> future) throws Exception {
                if (log.isDebugEnabled()) {
                    log.debug("{} Connection closed", nativeSocketAccessLink.channel());
                }
                if (null != tunnelLink.backhaulLinkPromise.getNow()) {
                    tunnelLink.backhaulLinkPromise.getNow().close();
                }
                tunnelLinkMap.remove(accessRequestId, tunnelLink);
            }
        });

        return webSocketBackhaulLinkPromise;
    }

    /**
     * 隧道打开.
     *
     * @param backhaulLink 回程链路
     * @param handshake    通道打开时握手信息
     */
    private void tunnelResponded(final ChannelHandlerContext backhaulLink, final HandshakeComplete handshake) {
        backhaulLink.channel().config().setAutoRead(false);

        // XXX 考虑是否验证来源.
        final String accessRequestId = determineQueryParameters(handshake.requestUri()).get("id");
        final TunnelLink tunnelLink = tunnelLinkMap.get(accessRequestId);
        if (null != tunnelLink) {
            tunnelLink.backhaulLinkPromise.setSuccess(backhaulLink);
        } else {
            log.warn("{} The corresponding tunnel access link cannot be found, it may have timed out: {}", backhaulLink.channel(), accessRequestId);
            WebSocketUtils.goingAwayClose(backhaulLink, "TUNNEL_REQUEST_NOT_FOUND");
        }
    }

    private void tunnelManagement(final ChannelHandlerContext webSocketTunnelContext, final HandshakeComplete handshake) throws IOException {
        webSocketTunnelContext.channel().config().setAutoRead(false);
        webSocketTunnelContext.pipeline().remove(webSocketTunnelContext.handler());

        final PipedOutputStream out = new PipedOutputStream();
        final PipedInputStream innerIn = new PipedInputStream(out);
        final OutputStream innerOut = new WebSocketBinaryOutputStream(webSocketTunnelContext);
        final Terminal terminal = new WebSocketTerminal();
        final LineReader reader = new ConsoleLineReader(this, innerIn, innerOut, terminal);
        new WebSocketTunnelShell(this, reader, new PrintStream(innerOut)).start();

        webSocketTunnelContext.pipeline().addLast(new SimpleChannelInboundHandler<WebSocketFrame>() {
            @Override
            protected void channelRead0(final ChannelHandlerContext ctx, final WebSocketFrame msg) throws Exception {
                if (msg instanceof BinaryWebSocketFrame) {
                    out.write(ByteBufUtil.getBytes(msg.content()));
                    out.flush();
                } else if (msg instanceof TextWebSocketFrame) {
                    final String message = ((TextWebSocketFrame) msg).text();
                    final int index = message.indexOf(' ');
                    final String command = -1 < index ? message.substring(0, index) : message;
                    final String commandArgs = -1 < index ? message.substring(index + 1) : "";
                    if ("\u0009\u0011".equals(command)) {
                        final String[] dimension = commandArgs.split("x", 2);
                        try {
                            final int cols = Integer.valueOf(dimension[0]);
                            final int rows = Integer.valueOf(dimension[1]);
                            ((WebSocketTerminal) terminal).setCols(cols);
                            ((WebSocketTerminal) terminal).setRows(rows);
                        } catch (final NumberFormatException ignore) {
                            log.error("Execute command '{}' error", message, ignore);
                        }
                        return;
                    }
                }
            }
        });

        webSocketTunnelContext.channel().config().setAutoRead(true);
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
    public boolean kill(final String linkId) {
        final TunnelLink tunnelLink = tunnelLinkMap.get(linkId);
        if (null != tunnelLink) {
            final ChannelHandlerContext backhaul = tunnelLink.backhaulLinkPromise.getNow();
            tunnelLink.accessLink.channel().close();
            backhaul.channel().close();
            return true;
        }
        return false;
    }

    /**
     * 关闭服务器.
     */
    public void shutdownGracefully() {
        if (null != primaryServerChannel) {
            primaryServerChannel.close();
        }
        bossGroup.shutdownGracefully();
        workerGroup.shutdownGracefully();
    }

    /*- *************************
     *
     *
     * ************************ */

    public Channel forward(final int listenPort, final String node, final String toHost, final int toPort) throws InterruptedException {
        final Broker nodeChannel = this.lookupBroker(node);
        if (null == nodeChannel) {
            throw new IllegalStateException("TUNNEL_NOT_FOUND:" + node);
        }

        final Channel listenChannel = this.listenTcp(this.listenHost, listenPort, node, new ChannelInitializer<SocketChannel>() {
            @Override
            protected void initChannel(final SocketChannel nativeSocketChannel) {
                nativeSocketChannel.config().setAutoRead(false);
                nativeSocketChannel.pipeline().addLast(new ChannelInboundHandlerAdapter() {
                    @Override
                    public void channelActive(final ChannelHandlerContext accessLink) throws Exception {
                        final String forwardRequestId = "tcp:" + id(nativeSocketChannel);
                        final Promise<ChannelHandlerContext> backhaulPromise = nativeTunnelRequested(node, forwardRequestId, accessLink);

                        // XXX find agent and send connection request.
                        final String forwardTarget = "tcp://" + toHost + ":" + toPort;
                        final String forwardRequest = forwardRequestId + "->" + forwardTarget;
                        if (log.isDebugEnabled()) {
                            log.debug("{} Try open native tunnel: {}", nativeSocketChannel, forwardRequest);
                        }
                        nodeChannel.bus.writeAndFlush(new TextWebSocketFrame(forwardRequest));

                        final boolean responded = backhaulPromise.await(backhaulLinkTimeoutMs, TimeUnit.MILLISECONDS);
                        if (!responded) {
                            backhaulPromise.tryFailure(new ConnectTimeoutException("Timeout"));
                        }
                    }
                });
            }
        });

        final BrokerForwarding rule = new BrokerForwarding(listenChannel, nodeChannel, toHost + ":" + toPort);
        tcpListenChannelMap.putIfAbsent(node, new CopyOnWriteArrayList<Channel>());
        tcpForwardRuleMap.put(listenPort, rule);

        listenChannel.closeFuture().addListener(new ChannelFutureListener() {
            @Override
            public void operationComplete(final ChannelFuture future) throws Exception {
                List<Channel> channels = tcpListenChannelMap.get(node);
                if (null != channels) {
                    channels.remove(listenChannel);
                }
                tcpForwardRuleMap.remove(listenPort, rule);
            }
        });
        List<Channel> channels = tcpListenChannelMap.get(node);
        channels.add(listenChannel);
        return listenChannel;
    }

    public boolean unforward(final int port) {
        final BrokerForwarding brokerForwarding = tcpForwardRuleMap.remove(port);
        if (null != brokerForwarding) {
            brokerForwarding.serverChannel.close();
            return true;
        }
        return false;
    }

    /**
     * 监听 TCP 端口.
     *
     * @param listenHost  监听主机名
     * @param listenPort  监听端口
     * @param namePrefix  线程名称前缀
     * @param initializer 请求处理初始化器
     * @return 服务监听channel
     */
    private Channel listenTcp(final String listenHost, final int listenPort,
                              final String namePrefix, final ChannelHandler initializer) throws InterruptedException {
        final NioEventLoopGroup socketBossGroup = new NioEventLoopGroup(1, new DefaultThreadFactory(namePrefix + "-forward-boss", false));
        final NioEventLoopGroup socketWorkerGroup = new NioEventLoopGroup(0, new DefaultThreadFactory(namePrefix + "-forward-workers", false));
        final ServerBootstrap socketBootstrap = new ServerBootstrap();
        socketBootstrap.option(ChannelOption.SO_REUSEADDR, true);
        socketBootstrap.childOption(ChannelOption.TCP_NODELAY, true);
        socketBootstrap.group(socketBossGroup, socketWorkerGroup).channel(NioServerSocketChannel.class);
        socketBootstrap.handler(new LoggingHandler(LogLevel.INFO)).childHandler(initializer);
        if (null == listenHost) {
            return socketBootstrap.bind(listenPort).sync().channel();
        } else {
            return socketBootstrap.bind(listenHost, listenPort).sync().channel();
        }
    }

    /*- ****************************
     *
     *
     *
     * *************************** */

    /**
     * 获取所有注册的节点.
     *
     * @return 节点名称
     */
    public Collection<Broker> getNodes() {
        return brokerRegistry.values();
    }

    /**
     * 获取所有转发规则.
     *
     * @return 转发规则清单
     */
    public Collection<BrokerForwarding> getAccessRules() {
        final List<BrokerForwarding> rules = new LinkedList<>();
        for (final Map.Entry<String, Broker> entry : brokerRegistry.entrySet()) {
            rules.add(new BrokerForwarding(primaryServerChannel, entry.getValue(), "*ws*"));
        }
        rules.addAll(tcpForwardRuleMap.values());
        return rules;
    }

    /**
     * 获取转发的明细.
     *
     * @param rule 转发规则
     */
    public List<TunnelLink> getTunnelLink(final BrokerForwarding rule) {
        final List<TunnelLink> candidates = new LinkedList<>();
        for (final TunnelLink link : tunnelLinkMap.values()) {
            int port = ((InetSocketAddress) link.accessLink.channel().localAddress()).getPort();
            int port2 = ((InetSocketAddress) rule.serverChannel.localAddress()).getPort();
            final String node = rule.node.name;
            String node2 = link.nodeKey;
            if (port == port2 && node.equals(node2)) {
                candidates.add(link);
            }
        }
        return candidates;
    }

    /* ********************************** */

    /**
     * 节点信息.
     */
    public class Broker {
        private final String id;
        private final String name;
        private final String version;
        private final String extranet;
        private final String intranet;
        private final ChannelHandlerContext bus;

        Broker(final String id, final String name, final String version,
               final String extranet, final String intranet, final ChannelHandlerContext bus) {
            this.id = id;
            this.name = name;
            this.version = version;
            this.extranet = extranet;
            this.intranet = intranet;
            this.bus = bus;
        }

        public String name() {
            return name;
        }

        public void close() {
            bus.close();
        }

        @Override
        public String toString() {
            return id + ",\t" + name + "\t" + version + "\t" + extranet + "\t" + intranet;
        }
    }

    /**
     * 隧道信息.
     */
    public class TunnelLink {
        /**
         * 接入ID.
         */
        private final String id;

        /**
         * 代理节点.
         */
        private final String nodeKey;

        /**
         * 接入链路.
         */
        private final ChannelHandlerContext accessLink;

        /**
         * 回传链路.
         */
        private final Promise<ChannelHandlerContext> backhaulLinkPromise;

        TunnelLink(final String id, final String nodeKey,
                   final ChannelHandlerContext accessLink,
                   final Promise<ChannelHandlerContext> backhaulLinkPromise) {
            this.id = id;
            this.nodeKey = nodeKey;
            this.accessLink = accessLink;
            this.backhaulLinkPromise = backhaulLinkPromise;
        }

        @Override
        public String toString() {
            final Channel theRequest = accessLink.channel();
            final ChannelHandlerContext backhaulLink = backhaulLinkPromise.getNow();
            String description = "[" + id + ", " + theRequest.remoteAddress() + " -> " + theRequest.localAddress();
            if (null != backhaulLink) {
                final Channel theResponse = backhaulLink.channel();
                description += " >< " + theResponse.localAddress() + " <- " + theResponse.remoteAddress() + "]";
            } else {
                description += " ><  ?]";
            }
            return description;
        }
    }

    /**
     * 隧道映射.
     */
    public class BrokerForwarding {
        private final Channel serverChannel;
        private final Broker node;
        private final String target;

        private BrokerForwarding(final Channel serverChannel, final Broker node, final String target) {
            this.serverChannel = serverChannel;
            this.node = node;
            this.target = target;
        }

        public Broker getNode() {
            return node;
        }

        @Override
        public String toString() {
            final SocketAddress localAddr = serverChannel.localAddress();
            final String nodeName = node.name;
            final String nodeAddress = node.intranet + "%" + node.extranet;
            return localAddr + " -> " + nodeName + "[" + nodeAddress + "] -> " + target;
        }
    }

    /**
     *
     */
    class WebSocketBinaryOutputStream extends OutputStream {
        private final ChannelHandlerContext webSocketContext;

        WebSocketBinaryOutputStream(final ChannelHandlerContext webSocketContext) {
            this.webSocketContext = webSocketContext;
        }

        @Override
        public void write(final byte[] b, final int off, final int len) throws IOException {
            try {
                /*-
                 * await: 不等待多线程写入时会丢失数据或多次发送相同数据.
                 */
                webSocketContext.writeAndFlush(new BinaryWebSocketFrame(Unpooled.wrappedBuffer(b, off, len))).await();
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException(e.getMessage());
            }
        }

        @Override
        public void write(final int b) throws IOException {
            this.write(new byte[]{(byte) b});
        }

        @Override
        public void flush() throws IOException {
            try {
                webSocketContext.writeAndFlush(Unpooled.EMPTY_BUFFER).await();
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException(e.getMessage());
            }
        }

        @Override
        public void close() {
            webSocketContext.close();
        }
    }
}

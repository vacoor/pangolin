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
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.ConnectTimeoutException;
import io.netty.channel.SimpleChannelInboundHandler;
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
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.util.SelfSignedCertificate;
import io.netty.handler.timeout.IdleStateEvent;
import io.netty.handler.timeout.IdleStateHandler;
import io.netty.util.concurrent.DefaultThreadFactory;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.FutureListener;
import io.netty.util.concurrent.GenericFutureListener;
import io.netty.util.concurrent.GlobalEventExecutor;
import io.netty.util.concurrent.Promise;
import jline.Terminal;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import javax.net.ssl.SSLException;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.URI;
import java.security.cert.CertificateException;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
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
     * 已注册的broker节点(id:broker).
     */
    private final ConcurrentMap<String, Broker> registeredBrokers = new ConcurrentHashMap<>();

    /**
     * 存活的连接信息(id:连接).
     */
    private final ConcurrentMap<String, Connection> connectionMap = new ConcurrentHashMap<>();

    /**
     * 开启的端口转发信息(port:转发信息).
     */
    private final ConcurrentMap<Integer, PortForwarding2> portForwardingMap2 = new ConcurrentHashMap<>();


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
    private final NioEventLoopGroup bossGroup = new NioEventLoopGroup(1, new DefaultThreadFactory("WebSocketTunnelServer-boss", true));

    /**
     * 处理 event loop group.
     */
    private final NioEventLoopGroup workerGroup = new NioEventLoopGroup(0, new DefaultThreadFactory("WebSocketTunnelServer-workers", true));

    /**
     *
     */
    private final AtomicBoolean startup = new AtomicBoolean(false);

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
    public Channel start() throws InterruptedException, CertificateException, SSLException {
        if (!startup.compareAndSet(false, true)) {
            return primaryServerChannel;
        }

        final SslContext sslContext = useSsl ? createSslContext() : null;
        return primaryServerChannel = listenTcp(listenHost, listenPort, bossGroup, workerGroup, new ChannelInitializer<SocketChannel>() {
            @Override
            protected void initChannel(final SocketChannel ch) throws Exception {
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
    }


    private Channel listenTcp(final String listenHost, final int listenPort,
                              final NioEventLoopGroup bossGroup, final NioEventLoopGroup workerGroup,
                              final ChannelHandler initializer) throws InterruptedException {
        final ServerBootstrap serverBootstrap = new ServerBootstrap();
        serverBootstrap.option(ChannelOption.SO_REUSEADDR, true);
        serverBootstrap.childOption(ChannelOption.TCP_NODELAY, true);
        serverBootstrap.group(bossGroup, workerGroup).channel(NioServerSocketChannel.class);
        serverBootstrap.childHandler(initializer);

        if (null == listenHost) {
            return serverBootstrap.bind(listenPort).sync().channel();
        } else {
            return serverBootstrap.bind(listenHost, listenPort).sync().channel();
        }
    }

    public void expiredCheck() {
        for (Map.Entry<String, Broker> entry : registeredBrokers.entrySet()) {
            if (!entry.getValue().bus.channel().isActive()) {
                log.warn("Expired: {}", entry.getValue());
                registeredBrokers.remove(entry.getKey());
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
                    brokerRegistered(webSocketContext, handshake);
                } else if (PROTOCOL_TUNNEL_REQUEST.equalsIgnoreCase(subprotocol)) {
                    /*-
                     * 隧道打开请求.
                     */
                    webSocketTunnelRequested(webSocketContext, handshake);
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
     * @param brokerKey 节点标识
     * @return 通信总线
     */
    public Broker lookupBroker(final String brokerKey) {
        return registeredBrokers.get(brokerKey);
    }

    /* ***************** 节点注册 [[ **************** */

    /**
     * 节点注册.
     *
     * @param webSocketContext 节点注册上下文
     * @param handshake        节点注册握手信息
     */
    private void brokerRegistered(final ChannelHandlerContext webSocketContext, final HandshakeComplete handshake) {
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
        if (null == registeredBrokers.putIfAbsent(nodeName, node)) {
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
     * @param broker  节点信息
     */
    private void nodeUnregistered(final String nodeKey, final Broker broker) {
        if (registeredBrokers.remove(nodeKey, broker)) {
            log.info("{} Node unregistered: {}", broker.bus.channel(), broker);

            try {
                this.onNodeUnregisteredClose(broker);
            } finally {
                if (broker.bus.channel().isOpen()) {
                    WebSocketUtils.normalClose(broker.bus, "UNREGISTER");
                }
            }
        } else {
            log.error("{} Node unregister failure: '{}' not found in registry", broker.bus.channel(), broker);
        }
    }

    /**
     * 节点取消注册关闭.
     */
    private void onNodeUnregisteredClose(final Broker broker) {
        /*-
         * 关闭所有对应的监听端口服务.
         */
        final Set<Integer> destroy = new TreeSet<>();
        for (final Map.Entry<Integer, PortForwarding2> mapping : portForwardingMap2.entrySet()) {
            final PortForwarding2 forwarding = mapping.getValue();
            if (broker.equals(forwarding.getBroker())) {
                destroy.add(mapping.getKey());
            }
        }
        for (final Integer port : destroy) {
            portForwardingMap2.remove(port).listenChannel.close();
        }
        /*-
         * XXX 是否关闭所有对应的连接.
         */
    }

    /* ***************** ]] 节点注册 **************** */

    private void webSocketTunnelRequested(final ChannelHandlerContext webSocketAccessLink, final HandshakeComplete handshake) throws InterruptedException {
        final Map<String, String> parameters = determineQueryParameters(handshake.requestUri());
        final String tunnel = parameters.get("tunnel");
        final String target = parameters.get("target");

        final ChannelHandlerContext bus = lookupBroker(tunnel).bus;
        if (null != bus) {
            final String id = "ws:" + id(webSocketAccessLink.channel());
            final Promise<ChannelHandlerContext> webSocketBackhaulPromise = webSocketTunnelRequested(id, tunnel, webSocketAccessLink);
            final String webSocketBackhaulRequest = id + "->" + target;

            if (log.isDebugEnabled()) {
                log.debug("{} Try open websocket tunnel: {}", webSocketAccessLink.channel(), webSocketBackhaulRequest);
            }

            bus.writeAndFlush(new TextWebSocketFrame(webSocketBackhaulRequest));
            waitBackhaulLinkUntilTimeout(webSocketBackhaulPromise);
        } else {
            log.warn("{} Not found tunnel: {}, will close", webSocketAccessLink.channel(), tunnel);
            WebSocketUtils.policyViolationClose(webSocketAccessLink, "BROKER_UNAVAILABLE");
        }
    }

    /**
     * 请求接入 WebSocket 隧道.
     *
     * @param accessRequestId     接入请求ID
     * @param webSocketAccessLink 接入链路
     * @return 用于设置回传链接的 promise
     */
    private Promise<ChannelHandlerContext> webSocketTunnelRequested(final String accessRequestId, final String nodeKey,
                                                                    final ChannelHandlerContext webSocketAccessLink) {
        if (log.isDebugEnabled()) {
            log.debug("{} WebSocket tunnel access link: {}", webSocketAccessLink.channel(), accessRequestId);
        }

        final Promise<ChannelHandlerContext> webSocketBackhaulLinkPromise = GlobalEventExecutor.INSTANCE.newPromise();
        final Connection connection = new Connection(accessRequestId, nodeKey, webSocketAccessLink, webSocketBackhaulLinkPromise);
        if (null != connectionMap.putIfAbsent(accessRequestId, connection)) {
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

                final ChannelHandlerContext webSocketBackhaulLink = connection.backhaulLinkPromise.getNow();
                if (null != webSocketBackhaulLink && webSocketBackhaulLink.channel().isOpen()) {
                    // XXX WebSocketUtils.normalClose(webSocketBackhaulLink, "");
                    webSocketBackhaulLink.close();
                }
                connectionMap.remove(accessRequestId, connection);
            }
        });

        return webSocketBackhaulLinkPromise;
    }

    private void waitBackhaulLinkUntilTimeout(final Promise<ChannelHandlerContext> backhaulPromise) throws InterruptedException {
        if (!backhaulPromise.await(backhaulLinkTimeoutMs, TimeUnit.MILLISECONDS)) {
            backhaulPromise.tryFailure(new ConnectTimeoutException("backhual link wait timeout"));
        }
    }

    /**
     * 请求接入 TCP 通道.
     *
     * @param accessRequestId        隧道请求 ID
     * @param nativeSocketAccessLink 请求接入的原生 socket链接
     * @return 用于设置回传链接的 promise
     */
    private Promise<ChannelHandlerContext> nativeTunnelRequested(final String nodeKey, final String accessRequestId, final ChannelHandlerContext nativeSocketAccessLink) {
        if (log.isDebugEnabled()) {
            log.debug("{} Native tunnel request: {}", nativeSocketAccessLink.channel(), accessRequestId);
        }

        final Promise<ChannelHandlerContext> webSocketBackhaulLinkPromise = GlobalEventExecutor.INSTANCE.newPromise();
        final Connection connection = new Connection(accessRequestId, nodeKey, nativeSocketAccessLink, webSocketBackhaulLinkPromise);
        if (null != connectionMap.putIfAbsent(accessRequestId, connection)) {
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
                if (null != connection.backhaulLinkPromise.getNow()) {
                    connection.backhaulLinkPromise.getNow().close();
                }
                connectionMap.remove(accessRequestId, connection);
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
        final Connection connection = connectionMap.get(accessRequestId);
        if (null != connection && !connection.backhaulLinkPromise.isDone()) {
            connection.backhaulLinkPromise.setSuccess(backhaulLink);
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
        final WebSocketTerminal terminal = new WebSocketTerminal();
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
                            final int cols = Integer.parseInt(dimension[0]);
                            final int rows = Integer.parseInt(dimension[1]);
                            terminal.setCols(cols);
                            terminal.setRows(rows);
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
        final Connection connection = connectionMap.get(linkId);
        if (null != connection) {
            final ChannelHandlerContext backhaul = connection.backhaulLinkPromise.getNow();
            connection.accessLink.channel().close();
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

    public Channel forward(final int listenPort, final String nodeKey, final String toHost, final int toPort) throws InterruptedException {
        final Broker broker = this.lookupBroker(nodeKey);
        if (null == broker) {
            throw new IllegalStateException("TUNNEL_NOT_FOUND:" + nodeKey);
        }

        final String target = "tcp://" + toHost + ":" + toPort;
        final Channel listenChannel = this.listenTcp(this.listenHost, listenPort, nodeKey, new ChannelInitializer<SocketChannel>() {
            @Override
            protected void initChannel(final SocketChannel nativeSocketChannel) {
//                nativeSocketChannel.config().setAutoRead(false);
                nativeSocketChannel.pipeline().addLast(new ChannelInboundHandlerAdapter() {

                    @Override
                    public void channelRegistered(final ChannelHandlerContext accessLink) throws Exception {
//                        super.channelRegistered(ctx);
//                    }

//                    @Override
//                    public void channelActive(final ChannelHandlerContext accessLink) throws Exception {
                        nativeSocketChannel.config().setAutoRead(false);
                        final String backhaulId = "tcp:" + id(nativeSocketChannel);
                        final Promise<ChannelHandlerContext> backhaulLinkPromise = nativeTunnelRequested(nodeKey, backhaulId, accessLink);

                        // XXX find agent and send connection request.
                        final String backhaulRequest = backhaulId + "->" + target;
                        if (log.isDebugEnabled()) {
                            log.debug("{} Try open native tunnel: {}", nativeSocketChannel, backhaulRequest);
                        }
                        broker.bus.writeAndFlush(new TextWebSocketFrame(backhaulRequest));
                        waitBackhaulLinkUntilTimeout(backhaulLinkPromise);
                    }
                });
            }
        });

        final PortForwarding2 pf = new PortForwarding2(listenPort, listenChannel, broker, target);
        portForwardingMap2.putIfAbsent(listenPort, pf);

//        final PortForwarding forwarding = new PortForwarding(listenChannel, broker, toHost + ":" + toPort);
//        tcpForwardRuleMap.put(listenPort, forwarding);
//        tcpListenChannelMap.putIfAbsent(nodeKey, new CopyOnWriteArrayList<Channel>());

        listenChannel.closeFuture().addListener(new ChannelFutureListener() {
            @Override
            public void operationComplete(final ChannelFuture future) throws Exception {
                portForwardingMap2.remove(listenPort, pf);

//                List<Channel> channels = tcpListenChannelMap.get(nodeKey);
//                if (null != channels) {
//                    channels.remove(listenChannel);
//                }
//                tcpForwardRuleMap.remove(listenPort, forwarding);
            }
        });
//        List<Channel> channels = tcpListenChannelMap.get(nodeKey);
//        channels.add(listenChannel);
        return listenChannel;
    }

    @Getter
    @AllArgsConstructor
    public class PortForwarding2 {
        private final int listenPort;
        private final Channel listenChannel;

        private final Broker broker;
        private final String target;

        @Override
        public String toString() {
            final SocketAddress localAddr = listenChannel.localAddress();
            final String nodeName = broker.name;
            final String nodeAddress = broker.intranet + "%" + broker.extranet;
            return localAddr + " -> " + nodeName + "[" + nodeAddress + "] -> " + target;
        }
    }


    public boolean unforward(final int port) {
        final PortForwarding2 portForwarding = portForwardingMap2.remove(port);
        if (null != portForwarding) {
            portForwarding.listenChannel.close();
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
        final NioEventLoopGroup socketBossGroup = new NioEventLoopGroup(1, new DefaultThreadFactory(namePrefix + "-forward-serverChannel", false));
        final NioEventLoopGroup socketWorkerGroup = new NioEventLoopGroup(0, new DefaultThreadFactory(namePrefix + "-forward-workers", false));
        final Channel channel = listenTcp(listenHost, listenPort, socketBossGroup, socketWorkerGroup, initializer);
        channel.closeFuture().addListener(new ChannelFutureListener() {
            @Override
            public void operationComplete(final ChannelFuture future) {
                socketBossGroup.shutdownGracefully();
                socketWorkerGroup.shutdownGracefully();
            }
        });
        return channel;
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
    public Collection<Broker> getBrokers() {
        return registeredBrokers.values();
    }

    /**
     * 获取所有转发规则.
     *
     * @return 转发规则清单
     */
    public Collection<PortForwarding2> getAccessRules() {
        final List<PortForwarding2> rules = new LinkedList<>();
        rules.addAll(portForwardingMap2.values());
        return rules;
    }

    /**
     * 获取转发的明细.
     *
     * @param rule 转发规则
     */
    public List<Connection> getConnections(final PortForwarding2 rule) {
        final List<Connection> candidates = new LinkedList<>();
        for (final Connection link : connectionMap.values()) {
            int port = ((InetSocketAddress) link.accessLink.channel().localAddress()).getPort();
            int port2 = ((InetSocketAddress) rule.listenChannel.localAddress()).getPort();
            final String node = rule.broker.name;
            String node2 = link.nodeKey;
            if (port == port2 && node.equals(node2)) {
                candidates.add(link);
            }
        }
        return candidates;
    }

    /* ********************************** */

    /**
     * Broker 节点.
     */
    public class Broker {
        /**
         * 节点 ID.
         */
        private final String id;

        /**
         * 节点名称.
         */
        private final String name;

        /**
         * 节点版本.
         */
        private final String version;

        /**
         * 外部地址.
         */
        private final String extranet;

        /**
         * 内部地址.
         */
        private final String intranet;

        /**
         *
         */
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
     * 连接信息.
     */
    @Getter
    @AllArgsConstructor
    public class Connection {
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
    public class PortForwarding {
        private final Channel serverChannel;
        private final Broker node;
        private final String target;

        private PortForwarding(final Channel serverChannel, final Broker node, final String target) {
            this.serverChannel = serverChannel;
            this.node = node;
            this.target = target;
        }

        public Broker getNode() {
            return node;
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

package com.github.tube.server;

import com.github.tube.util.WebSocketForwarder;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
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
import io.netty.handler.codec.http.websocketx.CloseWebSocketFrame;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolHandler;
import io.netty.handler.codec.http.websocketx.extensions.compression.WebSocketServerCompressionHandler;
import io.netty.handler.logging.LogLevel;
import io.netty.handler.logging.LoggingHandler;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import io.netty.util.concurrent.DefaultThreadFactory;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.FutureListener;
import io.netty.util.concurrent.GenericFutureListener;
import io.netty.util.concurrent.GlobalEventExecutor;
import io.netty.util.concurrent.Promise;
import lombok.extern.slf4j.Slf4j;

import javax.net.ssl.SSLException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.URI;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * TODO DOC ME!.
 *
 * @author changhe.yang
 * @since 20210825
 */
@Slf4j
public class WebSocketTunnelServer {
    private static final int MAX_HTTP_CONTENT_LENGTH = Integer.MAX_VALUE;

    /**
     * 通道注册.
     */
    private static final String PROTOCOL_TUNNEL_REGISTER = "PASSIVE-REG";

    /**
     * 通道请求.
     */
    private static final String PROTOCOL_TUNNEL_REQUEST = "";

    /**
     * 通道打开.
     */
    private static final String PROTOCOL_TUNNEL_RESPONSE = "PASSIVE";

    private final String host = null;
    private final int port = 2345;
    private final boolean useSsl = false;

    private final NioEventLoopGroup bossGroup = new NioEventLoopGroup(1, new DefaultThreadFactory("WebSocketTunnelServer-boss", false));
    private final NioEventLoopGroup workerGroup = new NioEventLoopGroup(0, new DefaultThreadFactory("WebSocketTunnelServer-workers", false));


    private final AtomicBoolean startup = new AtomicBoolean(false);

    private Channel channel;

    private ConcurrentMap<String, ChannelHandlerContext> registeredTunnelBusMap = new ConcurrentHashMap<>();

    private ConcurrentMap<String, Promise<ChannelHandlerContext>> pendingSocketChannelMap = new ConcurrentHashMap<>();
    private ConcurrentMap<String, Promise<ChannelHandlerContext>> pendingWebSocketChannelMap = new ConcurrentHashMap<>();

    private ConcurrentMap<String, List<Channel>> forwardServerChannelMap = new ConcurrentHashMap<>();


    public void start() throws Exception {
        if (!startup.compareAndSet(false, true)) {
            return;
        }

        final SslContext context = useSsl ? createSslContext() : null;
        final String path = "/tunnel";

        final ServerBootstrap bootstrap = new ServerBootstrap();
        bootstrap.group(bossGroup, workerGroup)
                .channel(NioServerSocketChannel.class)
                .handler(new LoggingHandler(LogLevel.INFO))
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(final SocketChannel ch) throws Exception {
                        final ChannelPipeline pipeline = ch.pipeline();
                        if (null != context) {
                            pipeline.addLast(context.newHandler(ch.alloc()));
                        }
                        pipeline.addLast(new HttpServerCodec());
                        pipeline.addLast(new HttpObjectAggregator(MAX_HTTP_CONTENT_LENGTH));
                        pipeline.addLast(new WebSocketServerCompressionHandler());
                        pipeline.addLast(new WebSocketServerProtocolHandler(path, ",PASSIVE-REG,PASSIVE", true, MAX_HTTP_CONTENT_LENGTH, false, true));
                        pipeline.addLast(createWebSocketTunnelFrameHandler());
                    }
                });


        if (null != host) {
            channel = bootstrap.bind(host, port).sync().channel();
        } else {
            channel = bootstrap.bind(port).sync().channel();
        }
    }

    /**
     * Create an ssl context.
     *
     * @return ssl context
     */
    private SslContext createSslContext() throws SSLException {
        /*-
        <pre>
        final SelfSignedCertificate ssc = new SelfSignedCertificate();
        return SslContextBuilder.forServer(ssc.certificate(), ssc.privateKey()).build();
        </pre>
        */
        return SslContextBuilder.forClient().trustManager(InsecureTrustManagerFactory.INSTANCE).build();
    }

    private SimpleChannelInboundHandler<WebSocketFrame> createWebSocketTunnelFrameHandler() {
        return new SimpleChannelInboundHandler<WebSocketFrame>() {
            @Override
            public void userEventTriggered(final ChannelHandlerContext webSocketContext, final Object evt) throws Exception {
                if (evt instanceof WebSocketServerProtocolHandler.HandshakeComplete) {
                    final WebSocketServerProtocolHandler.HandshakeComplete handshake = (WebSocketServerProtocolHandler.HandshakeComplete) evt;
                    final String requestUri = handshake.requestUri();
                    final String subprotocol = null != handshake.selectedSubprotocol() ? handshake.selectedSubprotocol() : "";

                    if (PROTOCOL_TUNNEL_REGISTER.equalsIgnoreCase(subprotocol)) {
                        tunnelRegistered(webSocketContext, handshake);
                    } else if (PROTOCOL_TUNNEL_REQUEST.equalsIgnoreCase(subprotocol)) {
                        final String forwardRequestId = "ws:" + id(webSocketContext.channel());
                        final Promise<ChannelHandlerContext> webSocketTunnelPromise = webSocketTunnelRequested(forwardRequestId, webSocketContext);
                        // FIXME
                        final ChannelHandlerContext tunnelBus = lookupTunnelBus("default");

                        // FIXME
                        final String forwardRequest = forwardRequestId + "->ws://127.0.0.1:8080/ws/print";
                        if (log.isDebugEnabled()) {
                            log.debug("{} Try open websocket tunnel: {}", webSocketContext.channel(), forwardRequest);
                        }
                        tunnelBus.writeAndFlush(new TextWebSocketFrame(forwardRequest));
                        if (!webSocketTunnelPromise.await(20, TimeUnit.SECONDS)) {
                            webSocketTunnelPromise.tryFailure(new ConnectTimeoutException("Timeout"));
                        }
                    } else if (PROTOCOL_TUNNEL_RESPONSE.equalsIgnoreCase(subprotocol)) {
                        tunnelResponded(webSocketContext, handshake);
                    } else {
                        // XXX 1002 WebSocketCloseStatus.PROTOCOL_ERROR
                        webSocketContext.writeAndFlush(new CloseWebSocketFrame()).addListener(ChannelFutureListener.CLOSE);
                    }
                } else {
                    super.userEventTriggered(webSocketContext, evt);
                }
            }

            @Override
            protected void channelRead0(final ChannelHandlerContext ctx, final WebSocketFrame msg) throws Exception {
            }

        };
    }

    private Map<String, String> determineQueryParameters(final String uri) {
        final String rawQuery = URI.create(uri).getQuery();
        return null != rawQuery ? splitQuery(rawQuery) : Collections.<String, String>emptyMap();
    }

    /**
     * 查找通道通信总线.
     *
     * @param tunnel 通道标识
     * @return 通信总线
     */
    private ChannelHandlerContext lookupTunnelBus(final String tunnel) {
        return registeredTunnelBusMap.get(tunnel);
    }

    private void tunnelRegistered(final ChannelHandlerContext webSocketContext,
                                  final WebSocketServerProtocolHandler.HandshakeComplete handshake) throws Exception {
        SocketAddress socketAddress = webSocketContext.channel().remoteAddress();
        final String requestUri = handshake.requestUri();
        final Map<String, String> parameters = this.determineQueryParameters(requestUri);
        final String id = parameters.get("id");
        registeredTunnelBusMap.putIfAbsent(id, webSocketContext);

        final SocketAddress address = webSocketContext.channel().remoteAddress();
        if (address instanceof InetSocketAddress) {
            final String outerAddress = ((InetSocketAddress) address).getAddress().getHostAddress();
            System.out.println(outerAddress);
        }

        final HttpHeaders headers = handshake.requestHeaders();
        final String interAddress = headers.get("x-tunnel-address");

        webSocketContext.channel().closeFuture().addListener(new GenericFutureListener<Future<? super Void>>() {
            @Override
            public void operationComplete(final Future<? super Void> future) throws Exception {
                if (log.isDebugEnabled()) {
                    log.debug("{} Agent connection loosed", webSocketContext.channel());
                }
                tunnelUnregistered(webSocketContext, handshake);
            }
        });

        System.out.println("Agent: " + id + " registered");

        if ("default".equalsIgnoreCase(id)) {
            forward(8889, "default", "127.0.0.1", 80);
            // forward(2222, "SOCKET", "139.196.88.115", 22);
        }
    }

    protected void tunnelUnregistered(final ChannelHandlerContext webSocketContext,
                                      final WebSocketServerProtocolHandler.HandshakeComplete handshake) throws Exception {
        final String requestUri = handshake.requestUri();
        final Map<String, String> parameters = determineQueryParameters(requestUri);
        final String agentKey = parameters.get("id");

        final ChannelHandlerContext agent = registeredTunnelBusMap.get(agentKey);
        if (null != agent && !agent.channel().isActive()) {
            registeredTunnelBusMap.remove(agentKey);
            System.out.println("Remove agent: " + agentKey);

            final List<Channel> channels = forwardServerChannelMap.get(agentKey);
            if (null != channels) {
                for (Channel channel1 : channels) {
                    if (channel1.isOpen()) {
                        channel1.close();
                    }
                }
            }
        }
    }

    /**
     * 请求接入 WebSocket 通道.
     *
     * @param forwardRequestId 通道请求 ID
     * @param webSocketContext 请求接入的web socket
     * @return 用于设置通道的 promise
     */
    private Promise<ChannelHandlerContext> webSocketTunnelRequested(final String forwardRequestId,
                                                                    final ChannelHandlerContext webSocketContext) {
        if (log.isDebugEnabled()) {
            log.debug("{} WebSocket tunnel request: {}", webSocketContext.channel(), forwardRequestId);
        }

        final Promise<ChannelHandlerContext> webSocketTunnelPromise = GlobalEventExecutor.INSTANCE.newPromise();
        if (null != pendingWebSocketChannelMap.putIfAbsent(forwardRequestId, webSocketTunnelPromise)) {
            throw new IllegalStateException(String.format("%s request id '%s' is already used", webSocketContext.channel(), forwardRequestId));
        }

        webSocketContext.channel().config().setAutoRead(false);
        webSocketTunnelPromise.addListener(new FutureListener<ChannelHandlerContext>() {
            @Override
            public void operationComplete(final Future<ChannelHandlerContext> future) throws Exception {
                if (future.isSuccess()) {
                    final ChannelHandlerContext webSocketTunnelContext = future.getNow();
                    webSocketTunnelContext.channel().config().setAutoRead(true);

                    webSocketContext.pipeline().remove(webSocketContext.handler());
                    webSocketTunnelContext.pipeline().remove(webSocketTunnelContext.handler());

                    webSocketContext.pipeline().addLast(WebSocketForwarder.createPipeAdapter(webSocketTunnelContext.channel()));
                    webSocketTunnelContext.pipeline().addLast(WebSocketForwarder.createPipeAdapter(webSocketContext.channel()));

                    webSocketContext.channel().config().setAutoRead(true);
                    webSocketTunnelContext.channel().config().setAutoRead(true);

                    if (log.isDebugEnabled()) {
                        log.debug("{} WebSocket tunnel open: {}", webSocketContext.channel(), webSocketTunnelContext.channel());
                    }
                } else {
                    final Throwable cause = future.cause();
                    if (log.isDebugEnabled()) {
                        log.debug("{} WebSocket tunnel open fail: {}", webSocketContext.channel(), cause.getMessage());
                    }
                    webSocketContext.channel().close();
                }
            }
        });
        webSocketContext.channel().closeFuture().addListener(new GenericFutureListener<Future<? super Void>>() {
            @Override
            public void operationComplete(final Future<? super Void> future) throws Exception {
                if (log.isDebugEnabled()) {
                    log.debug("{} Connection closed", webSocketContext.channel());
                }
                pendingWebSocketChannelMap.remove(forwardRequestId);
            }
        });

        return webSocketTunnelPromise;
    }

    /**
     * 请求接入原生通道.
     *
     * @param forwardRequestId    通道请求 ID
     * @param nativeSocketContext 请求接入的原生 socket
     * @return 用于设置通道的 promise
     */
    private Promise<ChannelHandlerContext> nativeTunnelRequested(final String forwardRequestId, final ChannelHandlerContext nativeSocketContext) {
        if (log.isDebugEnabled()) {
            log.debug("{} Native tunnel request: {}", nativeSocketContext.channel(), forwardRequestId);
        }

        final Promise<ChannelHandlerContext> webSocketTunnelPromise = GlobalEventExecutor.INSTANCE.newPromise();
        if (null != pendingSocketChannelMap.putIfAbsent(forwardRequestId, webSocketTunnelPromise)) {
            throw new IllegalStateException(String.format("%s request id '%s' is already used", nativeSocketContext.channel(), forwardRequestId));
        }

        nativeSocketContext.channel().config().setAutoRead(false);
        webSocketTunnelPromise.addListener(new FutureListener<ChannelHandlerContext>() {
            @Override
            public void operationComplete(final Future<ChannelHandlerContext> future) throws Exception {
                if (future.isSuccess()) {
                    final ChannelHandlerContext webSocketTunnelContext = future.getNow();
                    webSocketTunnelContext.channel().config().setAutoRead(false);

                    nativeSocketContext.pipeline().remove(nativeSocketContext.handler());
                    webSocketTunnelContext.pipeline().remove(webSocketTunnelContext.handler());

                    nativeSocketContext.pipeline().addLast(WebSocketForwarder.adaptNativeSocketToWebSocket(webSocketTunnelContext.channel()));
                    webSocketTunnelContext.pipeline().addLast(WebSocketForwarder.adaptWebSocketToNativeSocket(nativeSocketContext.channel()));

                    if (log.isDebugEnabled()) {
                        log.debug("{} Native tunnel open: {}", nativeSocketContext.channel(), webSocketTunnelContext.channel());
                    }

                    nativeSocketContext.channel().config().setAutoRead(true);
                    webSocketTunnelContext.channel().config().setAutoRead(true);
                } else {
                    final Throwable cause = future.cause();
                    if (log.isDebugEnabled()) {
                        log.debug("{} Native tunnel open fail: {}", nativeSocketContext.channel(), cause.getMessage());
                    }
                    nativeSocketContext.channel().close();
                }
            }
        });

        nativeSocketContext.channel().closeFuture().addListener(new GenericFutureListener<Future<? super Void>>() {
            @Override
            public void operationComplete(final Future<? super Void> future) throws Exception {
                if (log.isDebugEnabled()) {
                    log.debug("{} Connection closed", nativeSocketContext.channel());
                }
                pendingSocketChannelMap.remove(forwardRequestId);
            }
        });

        return webSocketTunnelPromise;
    }

    /**
     * 通道打开.
     *
     * @param webSocketTunnelContext web socket 通道
     * @param handshake              通道打开时握手信息
     */
    private void tunnelResponded(final ChannelHandlerContext webSocketTunnelContext, final WebSocketServerProtocolHandler.HandshakeComplete handshake) {
        webSocketTunnelContext.channel().config().setAutoRead(false);

        final String requestUri = handshake.requestUri();
        final Map<String, String> parameters = determineQueryParameters(requestUri);
        final String forwardRequestId = parameters.get("id");
        final boolean isNative = forwardRequestId.startsWith("tcp:");
        final Promise<ChannelHandlerContext> webSocketTunnelPromise = isNative ? pendingSocketChannelMap.remove(forwardRequestId) : pendingWebSocketChannelMap.remove(forwardRequestId);
        if (null != webSocketTunnelPromise) {
            webSocketTunnelPromise.setSuccess(webSocketTunnelContext);
        } else {
            // XXX 这里是否发送 CloseWebSocketFrame
            log.warn("{} The corresponding tunnel request cannot be found, it may have timed out: {}", webSocketTunnelContext.channel(), forwardRequestId);
            webSocketTunnelContext.writeAndFlush(Unpooled.EMPTY_BUFFER).addListener(ChannelFutureListener.CLOSE);
        }
    }

    private String id(final Channel channel) {
        return "0x" + channel.id().asShortText();
    }

    /* **************************
     *
     * ************************ */

    public Channel forward(final int port, final String agent, final String remoteHost, final int remotePort) throws Exception {
        final Channel channel = doForward(port, agent, remoteHost, remotePort);
        channel.closeFuture().addListener(new ChannelFutureListener() {
            @Override
            public void operationComplete(final ChannelFuture future) throws Exception {
                List<Channel> channels = forwardServerChannelMap.get(agent);
                if (null != channels) {
                    channels.remove(channel);
                }
            }
        });
        forwardServerChannelMap.putIfAbsent(agent, new CopyOnWriteArrayList<Channel>());
        List<Channel> channels = forwardServerChannelMap.get(agent);
        channels.add(channel);
        return channel;
    }

    private Channel doForward(final int port, final String agent, final String remoteHost, final int remotePort) throws InterruptedException {
        final ChannelHandlerContext agentContext = registeredTunnelBusMap.get(agent);
        if (null == agentContext) {
            throw new IllegalStateException("Agent not found: " + agent);
        }

        final NioEventLoopGroup socketBossGroup = new NioEventLoopGroup(1, new DefaultThreadFactory("socket-boss", false));
        final NioEventLoopGroup socketWorkerGroup = new NioEventLoopGroup(0, new DefaultThreadFactory("socket-workers", false));
        final ServerBootstrap socketBootstrap = new ServerBootstrap();
        socketBootstrap.childOption(ChannelOption.TCP_NODELAY, true);
        socketBootstrap.group(socketBossGroup, socketWorkerGroup).channel(NioServerSocketChannel.class);
        socketBootstrap.handler(new LoggingHandler(LogLevel.INFO)).childHandler(createNativeSocketForwardInitializer(agentContext, remoteHost, remotePort));
        if (null == host) {
            return socketBootstrap.bind(port).sync().channel();
        } else {
            return socketBootstrap.bind(host, port).sync().channel();
        }
    }


    /**
     * TCP 连接处理器.
     *
     * @return
     */
    private ChannelInitializer<SocketChannel> createNativeSocketForwardInitializer(final ChannelHandlerContext agentContext, final String hostname, final int port) {
        final String target = "tcp://" + host + ":" + port;
        return new ChannelInitializer<SocketChannel>() {
            @Override
            protected void initChannel(final SocketChannel nativeSocketChannel) {
                /*
                final Promise<ChannelHandlerContext> webSocketPromise = GlobalEventExecutor.INSTANCE.newPromise();

                if (log.isDebugEnabled()) {
                    log.debug("{} pipe request received", nativeSocketChannel);
                }
                if (null != pendingSocketChannelMap.putIfAbsent(forwardRequestId, webSocketPromise)) {
                    throw new IllegalStateException(String.format("%s request id '%s' is already used", nativeSocketChannel, forwardRequestId));
                }
                */

                // nativeSocketChannel.config().setAutoRead(false);
                nativeSocketChannel.pipeline().addLast(new ChannelInboundHandlerAdapter() {
                    @Override
                    public void channelActive(final ChannelHandlerContext nativeSocketContext) throws Exception {
                        final String forwardRequestId = "tcp:0x" + nativeSocketChannel.id().asShortText();
                        final Promise<ChannelHandlerContext> webSocketTunnelPromise = nativeTunnelRequested(forwardRequestId, nativeSocketContext);

                        /*-
                         * XXX find agent and send connection request.
                         */
                        final String forwardTarget = "tcp://" + hostname + ":" + port;
                        final String forwardRequest = forwardRequestId + "->" + forwardTarget;
                        if (log.isDebugEnabled()) {
                            log.debug("{} Try open native tunnel: {}", nativeSocketChannel, forwardRequest);
                        }
                        agentContext.channel().writeAndFlush(new TextWebSocketFrame(forwardRequest));

                        final boolean responded = webSocketTunnelPromise.await(20, TimeUnit.SECONDS);
                        if (!responded) {
                            webSocketTunnelPromise.tryFailure(new ConnectTimeoutException("Timeout"));
                        }
                        /*-
                        final boolean responded = webSocketPromise.await(30, TimeUnit.SECONDS);
                        if (responded) {
                            final ChannelHandlerContext webSocketContext = webSocketPromise.getNow();

                            if (log.isDebugEnabled()) {
                                log.debug("{} pipe response received {}", nativeSocketChannel, webSocketContext.channel());
                            }

                            webSocketContext.pipeline().removeLast();
                            nativeSocketContext.pipeline().remove(this);

                            webSocketContext.pipeline().addLast(WebSocketForwarder.adaptWebSocketToNativeSocket(nativeSocketContext.channel()));
                            nativeSocketContext.pipeline().addLast(WebSocketForwarder.adaptNativeSocketToWebSocket(webSocketContext.channel()));

                            if (log.isDebugEnabled()) {
                                log.debug("{} pipe channel to {}", nativeSocketChannel, webSocketContext.channel());
                            }

                            webSocketContext.channel().config().setAutoRead(true);
                            nativeSocketChannel.config().setAutoRead(true);
                        } else {
                            if (log.isDebugEnabled()) {
                                log.debug("{} wait pipe response TIMEOUT", nativeSocketChannel);
                            }
                            nativeSocketContext.close();
                        }
                        */
                    }
                });

                /*
                nativeSocketChannel.closeFuture().addListener(new GenericFutureListener<Future<? super Void>>() {
                    @Override
                    public void operationComplete(final Future<? super Void> future) throws Exception {
                        if (log.isDebugEnabled()) {
                            log.debug("{} Connection closed", nativeSocketChannel);
                        }
                        pendingSocketChannelMap.remove(forwardRequestId);
                    }
                });
                */

                /*-
                 * XXX find agent and send connection request.
                 */
                /*
                final String request = forwardRequestId + "->" + target;
                if (log.isDebugEnabled()) {
                    log.debug("{} send pipe request: {}", nativeSocketChannel, request);
                }
                agentContext.channel().writeAndFlush(new TextWebSocketFrame(request));
                */
            }
        };
    }


    public void shutdownGracefully() {
        if (null != channel) {
            channel.close();
        }
        bossGroup.shutdownGracefully();
        workerGroup.shutdownGracefully();

    }

    /**
     * Splits query string to map.
     *
     * @param query the query string
     * @return the name-value pairs
     */
    public static Map<String, String> splitQuery(final String query) {
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

    public static void main(String[] args) throws Exception {
        WebSocketTunnelServer webSocketTunnelServer = new WebSocketTunnelServer();
        webSocketTunnelServer.start();

    }
}

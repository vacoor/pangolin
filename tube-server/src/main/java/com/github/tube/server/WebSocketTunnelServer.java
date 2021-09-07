package com.github.tube.server;

import com.github.tube.server.shell.ConsoleLineReader;
import com.github.tube.server.shell.LineReader;
import com.github.tube.server.shell.WebSocketTerminal;
import com.github.tube.server.shell.WebSocketTunnelShell;
import com.github.tube.util.WebSocketForwarder;
import com.github.tube.util.WebSocketUtils;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBufUtil;
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
import io.netty.handler.codec.http.websocketx.BinaryWebSocketFrame;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolHandler;
import io.netty.handler.codec.http.websocketx.extensions.compression.WebSocketServerCompressionHandler;
import io.netty.handler.logging.LogLevel;
import io.netty.handler.logging.LoggingHandler;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.util.SelfSignedCertificate;
import io.netty.util.concurrent.DefaultThreadFactory;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.FutureListener;
import io.netty.util.concurrent.GenericFutureListener;
import io.netty.util.concurrent.GlobalEventExecutor;
import io.netty.util.concurrent.Promise;
import jline.Terminal;
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
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * WebSocket 通道服务.
 *
 * @author changhe.yang
 * @since 20210825
 */
@Slf4j
public class WebSocketTunnelServer {
    private static final String EMPTY = "";

    /**
     * 最大 HTTP 内容长度.
     */
    private static final int MAX_HTTP_CONTENT_LENGTH = 1024 * 1024 * 8;

    /**
     * 默认通道响应超时时间.
     */
    private static final long DEFAULT_TUNNEL_RESPONSE_TIMEOUT_MS = 20 * 1000L;

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

    /**
     * 服务管理.
     */
    private static final String PROTOCOL_TUNNEL_MANAGEMENT = "TUNNEL-MGR";

    /**
     * 支持的协议.
     */
    private static final String ALL_PROTOCOLS = PROTOCOL_TUNNEL_REQUEST + "," + PROTOCOL_TUNNEL_REGISTER + "," + PROTOCOL_TUNNEL_RESPONSE + "," + PROTOCOL_TUNNEL_MANAGEMENT;

    /**
     * 注册的通道(tunnel:tunnel-websocket).
     */
    private final ConcurrentMap<String, ChannelHandlerContext> registeredTunnelBusMap = new ConcurrentHashMap<>();

    /**
     * 等待 TCP 通道的连接(tunnel:request-socket-promise).
     */
    private final ConcurrentMap<String, Promise<ChannelHandlerContext>> pendingSocketChannelMap = new ConcurrentHashMap<>();

    /**
     * 等待 WebSocket 通道的连接(tunnel:request-websocket-promise).
     */
    private final ConcurrentMap<String, Promise<ChannelHandlerContext>> pendingWebSocketChannelMap = new ConcurrentHashMap<>();

    /**
     * 端口转发的服务连接(tunnel:server-channel).
     */
    private final ConcurrentMap<String, List<Channel>> socketForwardServerChannelMap = new ConcurrentHashMap<>();


    /**
     * 已开启的端口转发监听.
     */
    private final ConcurrentMap<Integer, Link> forwardTunnels = new ConcurrentHashMap<>();

    /**
     * 已开启的端口转发通道.
     */
    private final ConcurrentMap<String, List<TunnelInstance>> tunnelInstanceMap = new ConcurrentHashMap<>();

    /**
     * 服务 event loop group.
     */
    private final NioEventLoopGroup bossGroup = new NioEventLoopGroup(1, new DefaultThreadFactory("WebSocketTunnelServer-boss", true));

    /**
     * 处理 event loop group.
     */
    private final NioEventLoopGroup workerGroup = new NioEventLoopGroup(0, new DefaultThreadFactory("WebSocketTunnelServer-workers", true));
    private final AtomicBoolean startup = new AtomicBoolean(false);

    /**
     * 通道响应超时时间.
     */
    private long tunnelResponseTimeoutMs = DEFAULT_TUNNEL_RESPONSE_TIMEOUT_MS;

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


    private Channel serverChannel;

    public WebSocketTunnelServer(final int listenPort, final String endpointPath, final boolean useSsl) {
        this(null, listenPort, endpointPath, useSsl);
    }

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
     * @throws Exception 如果启动发生错误
     */
    public Channel start() throws CertificateException, SSLException, InterruptedException {
        if (!startup.compareAndSet(false, true)) {
            return serverChannel;
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
                        pipeline.addLast(new HttpServerCodec());
                        pipeline.addLast(new HttpObjectAggregator(MAX_HTTP_CONTENT_LENGTH));
                        pipeline.addLast(new WebSocketServerCompressionHandler());
                        pipeline.addLast(new WebSocketServerProtocolHandler(endpointPath, ALL_PROTOCOLS, true, MAX_HTTP_CONTENT_LENGTH, false, true));
                        pipeline.addLast(createWebSocketTunnelServerHandler());
                    }
                });

        if (null == listenHost) {
            serverChannel = bootstrap.bind(listenPort).sync().channel();
        } else {
            serverChannel = bootstrap.bind(listenHost, listenPort).sync().channel();
        }
        return serverChannel;
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
                if (!(evt instanceof WebSocketServerProtocolHandler.HandshakeComplete)) {
                    super.userEventTriggered(webSocketContext, evt);
                    return;
                }
                final WebSocketServerProtocolHandler.HandshakeComplete handshake = (WebSocketServerProtocolHandler.HandshakeComplete) evt;
                final String subprotocol = null != handshake.selectedSubprotocol() ? handshake.selectedSubprotocol() : EMPTY;
                if (PROTOCOL_TUNNEL_REGISTER.equalsIgnoreCase(subprotocol)) {
                    /*-
                     * 通道注册.
                     */
                    tunnelRegistered(webSocketContext, handshake);
                } else if (PROTOCOL_TUNNEL_REQUEST.equalsIgnoreCase(subprotocol)) {
                    /*-
                     * 通道打开请求.
                     */
                    final Map<String, String> parameters = determineQueryParameters(handshake.requestUri());
                    final String tunnel = parameters.get("tunnel");
                    final String target = parameters.get("target");
                    final ChannelHandlerContext tunnelBus = lookupTunnelBus(tunnel);
                    if (null != tunnelBus) {
                        final String forwardRequestId = "ws:" + id(webSocketContext.channel());
                        final Promise<ChannelHandlerContext> webSocketTunnelPromise = webSocketTunnelRequested(forwardRequestId, webSocketContext);
                        final String forwardRequest = forwardRequestId + "->" + target;

                        if (log.isDebugEnabled()) {
                            log.debug("{} Try open websocket tunnel: {}", webSocketContext.channel(), forwardRequest);
                        }
                        tunnelBus.writeAndFlush(new TextWebSocketFrame(forwardRequest));
                        if (!webSocketTunnelPromise.await(tunnelResponseTimeoutMs, TimeUnit.MILLISECONDS)) {
                            webSocketTunnelPromise.tryFailure(new ConnectTimeoutException("TUNNEL_WAIT_TIMOUT"));
                        }
                    } else {
                        log.warn("{} Not found tunnel: {}, will close", webSocketContext.channel(), tunnel);
                        WebSocketUtils.policyViolationClose(webSocketContext, "TUNNEL_NOT_FOUND");
                    }
                } else if (PROTOCOL_TUNNEL_RESPONSE.equalsIgnoreCase(subprotocol)) {
                    /*-
                     * 通道打开响应.
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
     * 查找通道通信总线.
     *
     * @param tunnel 通道标识
     * @return 通信总线
     */
    private ChannelHandlerContext lookupTunnelBus(final String tunnel) {
        return registeredTunnelBusMap.get(tunnel);
    }

    /**
     * 通道注册.
     *
     * @param webSocketContext websocket 上下文
     * @param handshake        websocket 握手信息
     * @throws Exception
     */
    private void tunnelRegistered(final ChannelHandlerContext webSocketContext,
                                  final WebSocketServerProtocolHandler.HandshakeComplete handshake) {
        final String requestUri = handshake.requestUri();
        final Map<String, String> parameters = this.determineQueryParameters(requestUri);
        final String tunnel = parameters.get("id");
        if (null == tunnel) {
            WebSocketUtils.policyViolationClose(webSocketContext, "ILLEGAL_TUNNEL_REGISTER");
            return;
        }
        if (null == registeredTunnelBusMap.putIfAbsent(tunnel, webSocketContext)) {
            webSocketContext.channel().closeFuture().addListener(new GenericFutureListener<Future<? super Void>>() {
                @Override
                public void operationComplete(final Future<? super Void> future) {
                    if (log.isDebugEnabled()) {
                        log.debug("{} Agent connection loosed", webSocketContext.channel());
                    }
                    tunnelUnregistered(webSocketContext, handshake);
                }
            });

            final HttpHeaders headers = handshake.requestHeaders();
            final String interAddress = headers.get("x-tunnel-address");

            String outerAddress = null;
            final SocketAddress address = webSocketContext.channel().remoteAddress();
            if (address instanceof InetSocketAddress) {
                outerAddress = ((InetSocketAddress) address).getAddress().getHostAddress();
            }

            log.info("{} Tunnel '{}' registered: {}/{}", webSocketContext.channel(), tunnel, outerAddress, interAddress);
        } else {
            log.warn("{} Tunnel register conflict, '{}' already registered, rejected", webSocketContext.channel(), tunnel);
            WebSocketUtils.policyViolationClose(webSocketContext, "TUNNEL_CONFLICT");
        }
    }

    /**
     * 通道取消注册.
     *
     * @param webSocketContext
     * @param handshake
     * @throws Exception
     */
    protected void tunnelUnregistered(final ChannelHandlerContext webSocketContext,
                                      final WebSocketServerProtocolHandler.HandshakeComplete handshake) {
        final String requestUri = handshake.requestUri();
        final Map<String, String> parameters = determineQueryParameters(requestUri);
        final String tunnel = parameters.get("id");

        final ChannelHandlerContext tunnelBus = registeredTunnelBusMap.remove(tunnel);
        if (null != tunnelBus) {
            log.info("{} Tunnel unregistered: {}", webSocketContext.channel(), tunnel);
            if (tunnelBus.channel().isOpen()) {
                WebSocketUtils.normalClose(tunnelBus, "UNREGISTER");
            }
        }

        // FIXME
        if (null != tunnelBus && !tunnelBus.channel().isActive()) {
            System.out.println("Remove agent: " + tunnel);

            final List<Channel> channels = socketForwardServerChannelMap.get(tunnel);
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
            public void operationComplete(final Future<ChannelHandlerContext> tunnelFuture) {
                if (tunnelFuture.isSuccess()) {
                    final ChannelHandlerContext webSocketTunnelContext = tunnelFuture.getNow();
                    webSocketTunnelContext.channel().config().setAutoRead(true);

                    pendingWebSocketChannelMap.remove(forwardRequestId);

                    webSocketContext.pipeline().remove(webSocketContext.handler());
                    webSocketTunnelContext.pipeline().remove(webSocketTunnelContext.handler());

                    webSocketContext.pipeline().addLast(WebSocketForwarder.pipe(webSocketTunnelContext.channel()));
                    webSocketTunnelContext.pipeline().addLast(WebSocketForwarder.pipe(webSocketContext.channel()));

                    webSocketContext.channel().config().setAutoRead(true);
                    webSocketTunnelContext.channel().config().setAutoRead(true);

                    if (log.isDebugEnabled()) {
                        log.debug("{} WebSocket tunnel open success: {}", webSocketContext.channel(), webSocketTunnelContext.channel());
                    }
                } else {
                    final Throwable cause = tunnelFuture.cause();
                    if (log.isDebugEnabled()) {
                        log.debug("{} WebSocket tunnel open failure: {}", webSocketContext.channel(), cause.getMessage());
                    }
                    WebSocketUtils.goingAwayClose(webSocketContext, cause.getMessage());
                }
            }
        });

        webSocketContext.channel().closeFuture().addListener(new GenericFutureListener<Future<? super Void>>() {
            @Override
            public void operationComplete(final Future<? super Void> future) {
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

        final String tunnel = nativeSocketContext.name();
        final Promise<ChannelHandlerContext> webSocketTunnelPromise = GlobalEventExecutor.INSTANCE.newPromise();
        if (null != pendingSocketChannelMap.putIfAbsent(forwardRequestId, webSocketTunnelPromise)) {
            throw new IllegalStateException(String.format("%s request id '%s' is already used", nativeSocketContext.channel(), forwardRequestId));
        }

        final TunnelInstance link = new TunnelInstance(forwardRequestId, nativeSocketContext);
        tunnelInstanceMap.putIfAbsent(tunnel, new CopyOnWriteArrayList<TunnelInstance>());
        tunnelInstanceMap.get(tunnel).add(link);

        nativeSocketContext.channel().config().setAutoRead(false);
        webSocketTunnelPromise.addListener(new FutureListener<ChannelHandlerContext>() {
            @Override
            public void operationComplete(final Future<ChannelHandlerContext> tunnelFuture) {
                if (tunnelFuture.isSuccess()) {
                    final ChannelHandlerContext webSocketTunnelContext = tunnelFuture.getNow();
                    webSocketTunnelContext.channel().config().setAutoRead(false);

                    pendingSocketChannelMap.remove(forwardRequestId);

                    nativeSocketContext.pipeline().remove(nativeSocketContext.handler());
                    webSocketTunnelContext.pipeline().remove(webSocketTunnelContext.handler());

                    nativeSocketContext.pipeline().addLast(WebSocketForwarder.adaptNativeSocketToWebSocket(webSocketTunnelContext.channel()));
                    webSocketTunnelContext.pipeline().addLast(WebSocketForwarder.adaptWebSocketToNativeSocket(nativeSocketContext.channel()));

                    nativeSocketContext.channel().config().setAutoRead(true);
                    webSocketTunnelContext.channel().config().setAutoRead(true);

                    if (log.isDebugEnabled()) {
                        log.debug("{} Native tunnel open success: {}", nativeSocketContext.channel(), webSocketTunnelContext.channel());
                    }
                    link.setResponse(webSocketTunnelContext);
                } else {
                    final Throwable cause = tunnelFuture.cause();
                    if (log.isDebugEnabled()) {
                        log.debug("{} Native tunnel open failure: {}", nativeSocketContext.channel(), cause.getMessage());
                    }
                    nativeSocketContext.close();
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
                tunnelInstanceMap.get(tunnel).remove(link);
                if (null != link.response) {
                    link.response.close();
                }
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
            log.warn("{} The corresponding tunnel request cannot be found, it may have timed out: {}", webSocketTunnelContext.channel(), forwardRequestId);
            WebSocketUtils.goingAwayClose(webSocketTunnelContext, "TUNNEL_REQUEST_NOT_FOUND");
        }
    }

    private static final Pattern FORWARD_CMD = Pattern.compile("^\\s*([-_a-zA-Z0-9]+)\\s+forward\\s+([1-9][0-9]{0,4})\\s+([^\\s:]+):([1-9][0-9]{0,4})\\s*$");

    private void tunnelManagement(final ChannelHandlerContext webSocketTunnelContext, final WebSocketServerProtocolHandler.HandshakeComplete handshake) throws IOException {
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

                    final Matcher matcher = FORWARD_CMD.matcher(message);
                    if (matcher.find()) {
                        final String tunnel = matcher.group(1);
                        final int localPort = Integer.parseInt(matcher.group(2));
                        final int remotePort = Integer.parseInt(matcher.group(4));
                        final String remoteHost = matcher.group(3);

                        try {
                            forward(localPort, tunnel, remoteHost, remotePort);
                            sendText(ctx, "OK");
                        } catch (final Exception ex) {
                            sendText(ctx, ex.getMessage());
                        }
                        return;
                    } else if ("list".equals(message)) {
                        sendText(ctx, registeredTunnelBusMap.keySet().toString());
                        return;
                    } else {
                        sendText(ctx, "NOT_SUPPORTED");
                    }
                }
            }
        });

        webSocketTunnelContext.channel().config().setAutoRead(true);
    }

    class WebSocketBinaryOutputStream extends OutputStream {
        private final ChannelHandlerContext webSocketContext;

        WebSocketBinaryOutputStream(final ChannelHandlerContext webSocketContext) {
            this.webSocketContext = webSocketContext;
        }

        @Override
        public void write(final byte[] b, final int off, final int len) throws IOException {
            try {
                /*-
                 * await 不等待多线程写入时会丢失数据或多次发送相同数据.
                 */
                webSocketContext.writeAndFlush(new BinaryWebSocketFrame(Unpooled.wrappedBuffer(b, off, len))).await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        @Override
        public void write(final int b) throws IOException {
            this.write(new byte[]{(byte) b});
        }

        @Override
        public void flush() throws IOException {
            webSocketContext.writeAndFlush(Unpooled.EMPTY_BUFFER);
        }

        @Override
        public void close() throws IOException {
            webSocketContext.close();
        }
    }

    private void sendText(final ChannelHandlerContext context, final String message) {
        // context.writeAndFlush(new BinaryWebSocketFrame(new TextWebSocketFrame(message).content()));
        context.writeAndFlush(new TextWebSocketFrame(message));
    }

    private String id(final Channel channel) {
        return "0x" + channel.id().asShortText();
    }

    /* **************************
     *
     * ************************ */

    public Channel forward(final int port, final String tunnel, final String remoteHost, final int remotePort) throws Exception {
        final Channel serverChannel = doForward(port, tunnel, remoteHost, remotePort);
        final ChannelHandlerContext tunnelBus = this.lookupTunnelBus(tunnel);

        final Link mapping = new Link(serverChannel, tunnel, tunnelBus, remoteHost + ":" + remotePort);
        forwardTunnels.put(port, mapping);

        serverChannel.closeFuture().addListener(new ChannelFutureListener() {
            @Override
            public void operationComplete(final ChannelFuture future) throws Exception {
                List<Channel> channels = socketForwardServerChannelMap.get(tunnel);
                if (null != channels) {
                    channels.remove(serverChannel);
                }
                forwardTunnels.remove(port);
            }
        });
        socketForwardServerChannelMap.putIfAbsent(tunnel, new CopyOnWriteArrayList<Channel>());
        List<Channel> channels = socketForwardServerChannelMap.get(tunnel);
        channels.add(serverChannel);
        return serverChannel;
    }

    public Set<String> getTunnelNames() {
        return Collections.unmodifiableSet(registeredTunnelBusMap.keySet());
    }

    public Set<Integer> getForwardPorts() {
        return Collections.unmodifiableSet(forwardTunnels.keySet());
    }

    public Collection<Link> getForwards() {
        return forwardTunnels.values();
    }

    public Map<String, List<TunnelInstance>> getForwardTunnels() {
        return Collections.unmodifiableMap(tunnelInstanceMap);
    }

    public boolean unforward(final int port) {
        final Link link = forwardTunnels.remove(port);
        if (null != link) {
            link.serverChannel.close();
            return true;
        }
        return false;
    }

    private Channel doForward(final int port, final String tunnel, final String remoteHost, final int remotePort) throws InterruptedException {
        final ChannelHandlerContext agentContext = registeredTunnelBusMap.get(tunnel);
        if (null == agentContext) {
            throw new IllegalStateException("TUNNEL_NOT_FOUND:" + tunnel);
        }

        final NioEventLoopGroup socketBossGroup = new NioEventLoopGroup(1, new DefaultThreadFactory("socket-boss", false));
        final NioEventLoopGroup socketWorkerGroup = new NioEventLoopGroup(0, new DefaultThreadFactory("socket-workers", false));
        final ServerBootstrap socketBootstrap = new ServerBootstrap();
        socketBootstrap.option(ChannelOption.SO_REUSEADDR, true);
        socketBootstrap.childOption(ChannelOption.TCP_NODELAY, true);
        socketBootstrap.group(socketBossGroup, socketWorkerGroup).channel(NioServerSocketChannel.class);
        socketBootstrap.handler(new LoggingHandler(LogLevel.INFO)).childHandler(createNativeSocketForwardInitializer(tunnel, agentContext, remoteHost, remotePort));
        if (null == listenHost) {
            return socketBootstrap.bind(port).sync().channel();
        } else {
            return socketBootstrap.bind(listenHost, port).sync().channel();
        }
    }


    /**
     * TCP 连接处理器.
     *
     * @return
     */
    private ChannelInitializer<SocketChannel> createNativeSocketForwardInitializer(final String tunnel, final ChannelHandlerContext tunnelBus, final String hostname, final int port) {
        return new ChannelInitializer<SocketChannel>() {
            @Override
            protected void initChannel(final SocketChannel nativeSocketChannel) {
                nativeSocketChannel.config().setAutoRead(false);
                nativeSocketChannel.pipeline().addLast(tunnel, new ChannelInboundHandlerAdapter() {
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
                        tunnelBus.writeAndFlush(new TextWebSocketFrame(forwardRequest));

                        final boolean responded = webSocketTunnelPromise.await(tunnelResponseTimeoutMs, TimeUnit.MILLISECONDS);
                        if (!responded) {
                            webSocketTunnelPromise.tryFailure(new ConnectTimeoutException("Timeout"));
                        }
                    }
                });
            }
        };
    }

    public void shutdownGracefully() {
        if (null != serverChannel) {
            serverChannel.close();
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

    public class Link {
        private final Channel serverChannel;
        private final String tunnel;
        private final ChannelHandlerContext tunnelBus;
        private final String target;

        private Link(final Channel serverChannel, final String tunnel, final ChannelHandlerContext tunnelBus, final String target) {
            this.serverChannel = serverChannel;
            this.tunnel = tunnel;
            this.tunnelBus = tunnelBus;
            this.target = target;
        }

        public String getTunnel() {
            return tunnel;
        }

        @Override
        public String toString() {
            final SocketAddress localAddr = serverChannel.localAddress();
            final SocketAddress tunnelAddr = tunnelBus.channel().remoteAddress();
            return localAddr + " -> " + tunnel + "[" + tunnelAddr + "] -> " + target;
        }
    }

    public class TunnelInstance {
        private final String id;
        private final ChannelHandlerContext request;
        private ChannelHandlerContext response;

        public TunnelInstance(final String id, final ChannelHandlerContext request) {
            this.id = id;
            this.request = request;
        }

        public boolean isPending() {
            return null == response;
        }

        public void setResponse(final ChannelHandlerContext response) {
            this.response = response;
        }

        @Override
        public String toString() {
            final Channel theRequest = request.channel();
            String description = "[" + id + ", " + theRequest.remoteAddress() + " -> " + theRequest.localAddress();
            if (null != response) {
                final Channel theResponse = response.channel();
                description += " >< " + theResponse.localAddress() + " <- " + theResponse.remoteAddress() + "]";
            } else {
                description += " ><  ?]";
            }
            return description;
        }
    }

    public Map<String, ChannelHandlerContext> getRegisteredTunnels() {
        return registeredTunnelBusMap;
    }

    public boolean kill(final String tunnelId) {
        Collection<List<TunnelInstance>> values = tunnelInstanceMap.values();
        for (List<TunnelInstance> value : values) {
            final Iterator<TunnelInstance> it = value.iterator();
            TunnelInstance found = null;
            while (it.hasNext()) {
                TunnelInstance next = it.next();
                if (next.id.equals(tunnelId)) {
                    found = next;
                    break;
                }
            }
            if (null != found) {
                found.request.close();
                value.remove(found);
                return true;
            }
        }
        return false;
    }
}

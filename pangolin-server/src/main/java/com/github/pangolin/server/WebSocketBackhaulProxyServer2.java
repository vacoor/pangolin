package com.github.pangolin.server;

import com.github.pangolin.server.shell.ConsoleLineReader;
import com.github.pangolin.server.shell.LineReader;
import com.github.pangolin.server.shell.ShellTerm;
import com.github.pangolin.server.shell.WebSocketBackhaulProxyServerShell;
import com.github.pangolin.util.Channels;
import com.github.pangolin.util.Redirects;
import com.github.pangolin.util.Util;
import com.github.pangolin.util.WebSocketUtils;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.ConnectTimeoutException;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.QueryStringDecoder;
import io.netty.handler.codec.http.websocketx.BinaryWebSocketFrame;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
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
import java.io.IOException;
import java.io.OutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.security.cert.CertificateException;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/**
 * WebSocket 隧道服务.
 *
 * @author changhe.yang
 * @since 20210825
 */
@Slf4j
public class WebSocketBackhaulProxyServer2 {
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

    private Discover discover;
    private Forwarder forwarder;

    /**
     * 创建隧道服务实例.
     *
     * @param listenPort   监听端口
     * @param endpointPath 接入点路径
     * @param useSsl       是否使用 SSL
     */
    public WebSocketBackhaulProxyServer2(final int listenPort, final String endpointPath, final boolean useSsl) {
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
    public WebSocketBackhaulProxyServer2(final String listenHost, final int listenPort, final String endpointPath, final boolean useSsl) {
        this.listenHost = listenHost;
        this.listenPort = listenPort;
        this.endpointPath = endpointPath;
        this.useSsl = useSsl;
        this.discover = new Discover();
        this.forwarder = new Forwarder(discover, bossGroup, workerGroup);
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
                        /*- 浏览器似乎处理压缩有问题(permessage-deflate).
                        new WebSocketServerCompressionHandler(),
                        new WebSocketServerProtocolHandler(endpointPath, ALL_PROTOCOLS, true, 65536, true, true),
                        */
                        new WebSocketServerProtocolHandler(endpointPath, ALL_PROTOCOLS, false, 65536, true, true),
                        // new IdleStateHandler(0, 0, 60, TimeUnit.SECONDS),
                        createWebSocketTunnelServerHandler()
                );
            }
        });
    }


    private Channel listenTcp(final String listenHost, final int listenPort,
                              final NioEventLoopGroup bossGroup, final NioEventLoopGroup workerGroup,
                              final ChannelHandler initializer) throws InterruptedException {
        final ChannelFuture serverChannelFuture = Channels.listen(listenHost, listenPort, bossGroup, workerGroup, initializer);

        if (null == listenHost) {
            return serverChannelFuture.sync().channel();
        } else {
            return serverChannelFuture.sync().channel();
        }
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
                    // agentRegistered(webSocketContext, handshake);
                } else if (PROTOCOL_TUNNEL_REQUEST.equalsIgnoreCase(subprotocol)) {
                    /*-
                     * 隧道打开请求.
                     */
                    webSocketTunnelRequested(webSocketContext, handshake);
                } else if (PROTOCOL_TUNNEL_RESPONSE.equalsIgnoreCase(subprotocol)) {
                    /*-
                     * 隧道回传打开响应.
                     */
                    // tunnelResponded(webSocketContext, handshake);
                    discover.tunnelResponded(handshake, webSocketContext);
                } else if (PROTOCOL_TUNNEL_MANAGEMENT.equalsIgnoreCase(subprotocol)) {
                    tunnelManagement(webSocketContext, handshake);
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

    /*- *******************************
     *
     *
     * ********************************/

    private void webSocketTunnelRequested(final ChannelHandlerContext accessCtx, final HandshakeComplete handshake) throws InterruptedException {
        final QueryStringDecoder decoder = new QueryStringDecoder(handshake.requestUri());
        final Map<String, List<String>> parameters = decoder.parameters();
        final String tunnel = Util.last(parameters, "tunnel");
        final String target = Util.last(parameters, "target");

            final String id = "ws:" + id(accessCtx.channel());

            if (log.isDebugEnabled()) {
                log.debug("{} Try open websocket tunnel: {}", accessCtx.channel(), target);
            }

            final Promise<ChannelHandlerContext> backhaulPromise = webSocketTunnelRequested(id, accessCtx, tunnel, URI.create(target));

            waitBackhaulLinkUntilTimeout(backhaulPromise);
    }

    /**
     * 请求接入 WebSocket 隧道.
     *
     * @param accessRequestId 接入请求ID
     * @param accessCtx       接入链路
     * @return 用于设置回传链接的 promise
     */
    private Promise<ChannelHandlerContext> webSocketTunnelRequested(final String accessRequestId, final ChannelHandlerContext accessCtx, final String nodeKey, final URI target) {
        accessCtx.channel().config().setAutoRead(false);
        Promise<ChannelHandlerContext> backhaulPromise = discover.tunnelRequested(accessRequestId, nodeKey, target, accessCtx);
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
        return backhaulPromise;
    }

    private void waitBackhaulLinkUntilTimeout(final Promise<ChannelHandlerContext> backhaulPromise) throws InterruptedException {
        if (!backhaulPromise.await(backhaulLinkTimeoutMs, TimeUnit.MILLISECONDS)) {
            backhaulPromise.tryFailure(new ConnectTimeoutException("backhual link wait timeout"));
        }
    }

    private void tunnelManagement(final ChannelHandlerContext webSocketTunnelContext, final HandshakeComplete handshake) throws IOException {
        webSocketTunnelContext.channel().config().setAutoRead(false);
        webSocketTunnelContext.pipeline().remove(webSocketTunnelContext.handler());

        final PipedOutputStream out = new PipedOutputStream();
        final PipedInputStream innerIn = new PipedInputStream(out);
        final OutputStream innerOut = new WebSocketBinaryOutputStream(webSocketTunnelContext);
        final ShellTerm terminal = new ShellTerm();
        final LineReader reader = new ConsoleLineReader(innerIn, innerOut, terminal, new Supplier<Collection<String>>() {
            @Override
            public Collection<String> get() {
                return Collections.emptyList();
            }
        });
//        new WebSocketBackhaulProxyServerShell(this, reader, new PrintStream(innerOut), null).start();

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
    public boolean kill(final String linkId) throws InterruptedException {
        return discover.kill(linkId);
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

    public void forward(final int listenPort, final String nodeKey, final String toHost, final int toPort) throws InterruptedException {
        forwarder.addForwarding(listenPort, nodeKey, new InetSocketAddress(toHost, toPort));
    }

    public boolean unforward(final int port) {
        return forwarder.removeForwarding(port);
    }

    /*- ****************************
     *
     *
     *
     * *************************** */

    /**
     * 获取所有转发规则.
     *
     * @return 转发规则清单
     */
    public Collection<Forwarder.Forwarding> getAccessRules() {
        return forwarder.getForwardings();
    }

    /* ********************************** */

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
                webSocketContext.channel().writeAndFlush(Unpooled.EMPTY_BUFFER).sync();
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

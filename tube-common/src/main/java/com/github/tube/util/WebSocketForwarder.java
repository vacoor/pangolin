package com.github.tube.util;

import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
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
import io.netty.channel.EventLoopGroup;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.http.DefaultHttpHeaders;
import io.netty.handler.codec.http.HttpClientCodec;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.websocketx.BinaryWebSocketFrame;
import io.netty.handler.codec.http.websocketx.CloseWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketClientHandshakerFactory;
import io.netty.handler.codec.http.websocketx.WebSocketClientProtocolHandler;
import io.netty.handler.codec.http.websocketx.WebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketVersion;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import io.netty.util.ReferenceCountUtil;
import io.netty.util.concurrent.DefaultThreadFactory;
import lombok.extern.slf4j.Slf4j;

import javax.net.ssl.SSLException;
import java.net.URI;

/**
 *
 */
@Slf4j
public class WebSocketForwarder {
    private static final int MAX_HTTP_CONTENT_LENGTH = Integer.MAX_VALUE;

    public static ChannelFuture forwardToWebSocket(final URI webSocketEndpoint1, final String webSocketProtocol1,
                                                   final URI webSocketEndpoint2, final String webSocketProtocol2) throws Exception {
        final EventLoopGroup workSocketGroup1 = new NioEventLoopGroup(1, new DefaultThreadFactory("WebSocket-FORWARD-1", true));
        return launch(
                webSocketEndpoint1,
                workSocketGroup1,
                new HttpClientCodec(),
                new HttpObjectAggregator(MAX_HTTP_CONTENT_LENGTH),
                newWebSocketClientProtocolHandler(webSocketEndpoint1, webSocketProtocol1),
                new SimpleChannelInboundHandler<WebSocketFrame>() {
                    @Override
                    public void channelActive(final ChannelHandlerContext webSocketContext) {
                        webSocketContext.channel().config().setAutoRead(false);
                        webSocketContext.channel().read();
                    }

                    @Override
                    public void userEventTriggered(final ChannelHandlerContext master, final Object evt) throws Exception {
                        if (!WebSocketClientProtocolHandler.ClientHandshakeStateEvent.HANDSHAKE_COMPLETE.equals(evt)) {
                            return;
                        }
                        master.channel().config().setAutoRead(false);

                        final EventLoopGroup webSocketGroup2 = new NioEventLoopGroup(1, new DefaultThreadFactory("WebSocket-FORWARD-2", true));
                        ChannelFuture future = null;
                        try {
                            future = launch(
                                    webSocketEndpoint2,
                                    webSocketGroup2,
                                    new HttpClientCodec(),
                                    new HttpObjectAggregator(MAX_HTTP_CONTENT_LENGTH),
                                    newWebSocketClientProtocolHandler(webSocketEndpoint2, webSocketProtocol2),
                                    new SimpleChannelInboundHandler<WebSocketFrame>() {
                                        @Override
                                        public void channelActive(final ChannelHandlerContext webSocketContext) {
                                            webSocketContext.channel().config().setAutoRead(false);
                                            webSocketContext.channel().read();
                                        }

                                        @Override
                                        public void userEventTriggered(final ChannelHandlerContext slave, final Object evt) {
                                            if (!WebSocketClientProtocolHandler.ClientHandshakeStateEvent.HANDSHAKE_ISSUED.equals(evt)) {
                                                return;
                                            }
                                            slave.channel().config().setAutoRead(false);

                                            slave.pipeline().remove(slave.handler());
                                            master.pipeline().remove(master.handler());

                                            slave.pipeline().addLast(pipe(master.channel()));
                                            master.pipeline().addLast(pipe(slave.channel()));

                                            slave.channel().config().setAutoRead(true);
                                            master.channel().config().setAutoRead(true);
                                        }

                                        @Override
                                        protected void channelRead0(final ChannelHandlerContext ctx, final WebSocketFrame msg) throws Exception {
                                            System.out.println(msg);
                                        }
                                    }
                            );
                        } finally {
                            if (null != future) {
                                future.addListener(new ChannelFutureListener() {
                                    @Override
                                    public void operationComplete(final ChannelFuture future) throws Exception {
                                        // FIXME
                                        master.close();
                                    }
                                });
                            } else {
                                // FIXME
                                master.close();
                            }
                        }
                    }

                    @Override
                    protected void channelRead0(final ChannelHandlerContext ctx, final WebSocketFrame msg) throws Exception {
                        System.out.println(msg);
                    }
                }
        );
    }


    public static ChannelFuture forwardToNativeSocket(final URI masterEndpoint, final String masterProtocol, final HttpHeaders headers,
                                                      final URI slaveEndpoint, final String traceId) throws Exception {
        final EventLoopGroup masterWebSocketGroup = new NioEventLoopGroup(1, new DefaultThreadFactory("WebSocket-PIPE-MASTER", true));
        final WebSocketClientProtocolHandler webSocketClientProtocolHandler = newWebSocketClientProtocolHandler(masterEndpoint, masterProtocol);
        return launch(
                masterEndpoint,
                masterWebSocketGroup,
                new HttpClientCodec(),
                new HttpObjectAggregator(MAX_HTTP_CONTENT_LENGTH),
                webSocketClientProtocolHandler,
                new SimpleChannelInboundHandler<WebSocketFrame>() {

                    @Override
                    public void channelActive(final ChannelHandlerContext ctx) {
                        ctx.channel().config().setAutoRead(false);
                        ctx.read();
                    }

                    @Override
                    public void userEventTriggered(final ChannelHandlerContext webSocketContext, final Object evt) throws Exception {
                        if (!WebSocketClientProtocolHandler.ClientHandshakeStateEvent.HANDSHAKE_COMPLETE.equals(evt)) {
                            return;
                        }

                        webSocketContext.channel().config().setAutoRead(false);

                        final EventLoopGroup slaveWebSocketGroup = new NioEventLoopGroup(1, new DefaultThreadFactory("WebSocket-PIPE-SLAVE", true));
                        ChannelFuture closeFuture = null;
                        try {
                            closeFuture = launch(slaveEndpoint, slaveWebSocketGroup, new ChannelInboundHandlerAdapter() {
                                @Override
                                public void channelActive(final ChannelHandlerContext nativeSocketContext) {
                                    nativeSocketContext.channel().config().setAutoRead(false);

                                    log.info("{} Connect ({})", nativeSocketContext.channel(), traceId);


                                    webSocketContext.pipeline().remove(webSocketContext.handler());
                                    nativeSocketContext.pipeline().remove(nativeSocketContext.handler());

                                    webSocketContext.pipeline().addLast(adaptWebSocketToNativeSocket(nativeSocketContext.channel()));
                                    nativeSocketContext.pipeline().addLast(adaptNativeSocketToWebSocket(webSocketContext.channel()));

                                    webSocketContext.channel().config().setAutoRead(true);
                                    nativeSocketContext.channel().config().setAutoRead(true);

                                    log.info("{} Connected ({})", webSocketContext.channel(), traceId);
                                    log.info("{} Connect to {} ({})", nativeSocketContext.channel(), webSocketContext.channel(), traceId);
                                }
                            });
                        } finally {
                            if (null != closeFuture) {
                                final Channel channel = closeFuture.channel();
                                closeFuture.addListener(new ChannelFutureListener() {
                                    @Override
                                    public void operationComplete(final ChannelFuture future) throws Exception {
                                        log.info("{} Connection closed: {}", channel, traceId);
                                        slaveWebSocketGroup.shutdownGracefully();
                                    }
                                });
                            } else {
                                slaveWebSocketGroup.shutdownGracefully();
                            }
                        }
                    }

                    @Override
                    protected void channelRead0(final ChannelHandlerContext ctx, final WebSocketFrame msg) throws Exception {
                        System.out.println("false2");
                        System.out.println(msg);
                    }

                    @Override
                    public void exceptionCaught(final ChannelHandlerContext webSocketContext, final Throwable cause) throws Exception {
                        log.warn("{} Software caused connection abort: {}", webSocketContext.channel(), cause.getMessage());
                        webSocketContext.writeAndFlush(Unpooled.EMPTY_BUFFER).addListener(ChannelFutureListener.CLOSE);
                    }
                }
        );
    }

    private static WebSocketClientProtocolHandler newWebSocketClientProtocolHandler(final URI endpoint, final String subprotocol) {
        return new WebSocketClientProtocolHandler(
                WebSocketClientHandshakerFactory.newHandshaker(endpoint, WebSocketVersion.V13, subprotocol, true, new DefaultHttpHeaders()),
                false
        );
    }

    private static ChannelFuture launch(final URI endpoint, final EventLoopGroup group, final ChannelHandler... handlers) throws Exception {
        final boolean isSecure = "wss".equalsIgnoreCase(endpoint.getScheme());
        final SslContext context = isSecure ? createSslContext() : null;

        ChannelFuture closeFuture = null;
        try {
            final Bootstrap b = new Bootstrap();
            b.option(ChannelOption.TCP_NODELAY, true);
            b.option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 5000);
            b.group(group).channel(NioSocketChannel.class).handler(new ChannelInitializer<SocketChannel>() {
                @Override
                protected void initChannel(final SocketChannel ch) {
                    final ChannelPipeline cp = ch.pipeline();
                    if (null != context) {
                        cp.addLast(context.newHandler(ch.alloc()));
                    }
                    cp.addLast(handlers);
                }
            });
            closeFuture = b.connect(endpoint.getHost(), endpoint.getPort()).sync().channel().closeFuture();
        } finally {
            if (null != closeFuture) {
                closeFuture.addListener(new ChannelFutureListener() {
                    @Override
                    public void operationComplete(final ChannelFuture future) throws Exception {
                        group.shutdownGracefully();
                    }
                });
            } else {
                group.shutdownGracefully();
            }
        }
        return closeFuture;
    }

    public static SslContext createSslContext() throws SSLException {
        return SslContextBuilder.forClient().trustManager(InsecureTrustManagerFactory.INSTANCE).build();
    }

    public static ChannelInboundHandlerAdapter pipe(final Channel targetChannel) {
        return new ChannelInboundHandlerAdapter() {
            @Override
            public void channelActive(final ChannelHandlerContext sourceChannelContext) throws Exception {
                sourceChannelContext.writeAndFlush(Unpooled.EMPTY_BUFFER);
            }

            @Override
            public void channelInactive(final ChannelHandlerContext sourceChannelContext) throws Exception {
                if (targetChannel.isActive()) {
                    targetChannel.writeAndFlush(Unpooled.EMPTY_BUFFER).addListener(ChannelFutureListener.CLOSE);
                }
            }

            @Override
            public void channelRead(final ChannelHandlerContext sourceChannelContext, final Object msg) throws Exception {
                if (targetChannel.isActive()) {
                    targetChannel.writeAndFlush(msg);
                } else {
                    ReferenceCountUtil.release(msg);
                }
            }

            @Override
            public void exceptionCaught(final ChannelHandlerContext sourceChannelContext, final Throwable cause) throws Exception {
                log.error("channel pipe to channel error: {}", cause.getMessage(), cause);
                sourceChannelContext.close();
            }
        };
    }

    /**
     * 将原生 Socket 适配到 WebSocket.
     *
     * @param webSocketChannel webSocket
     * @return 适配器
     */
    public static ChannelInboundHandlerAdapter adaptNativeSocketToWebSocket(final Channel webSocketChannel) {
        return new ChannelInboundHandlerAdapter() {
            @Override
            public void channelActive(final ChannelHandlerContext nativeSocketContext) {
                nativeSocketContext.writeAndFlush(Unpooled.EMPTY_BUFFER);
            }

            @Override
            public void channelInactive(final ChannelHandlerContext nativeSocketContext) {
                if (webSocketChannel.isActive()) {
                    if (log.isDebugEnabled()) {
                        log.debug("{} WebSocket Connection closed by {}", webSocketChannel, nativeSocketContext.channel());
                    }
                    webSocketChannel.writeAndFlush(Unpooled.EMPTY_BUFFER).addListener(ChannelFutureListener.CLOSE);
                }
            }

            @Override
            public void channelRead(final ChannelHandlerContext nativeSocketContext, final Object msg) throws Exception {
                if (webSocketChannel.isActive()) {
                    if (msg instanceof ByteBuf) {
                        if (log.isTraceEnabled()) {
                            log.trace("[SO]{} -> [WS]{}: {}", nativeSocketContext.channel(), webSocketChannel, ByteBufUtil.hexDump(((ByteBuf) msg)));
                        }
                        webSocketChannel.writeAndFlush(new BinaryWebSocketFrame((ByteBuf) msg));
                    } else {
                        throw new UnsupportedOperationException("Unexpect native-socket message: " + msg);
                    }
                } else {
                    ReferenceCountUtil.release(msg);
                }
            }

            @Override
            public void exceptionCaught(final ChannelHandlerContext nativeSocketContext, final Throwable cause) throws Exception {
                log.warn("{} Software caused connection abort: {}", nativeSocketContext.channel(), cause.getMessage());
                nativeSocketContext.close();
            }
        };
    }

    /**
     * 将 WebSocket 适配到原生 Socket.
     *
     * @param nativeSocketChannel 原生 socket
     * @return 适配器
     */
    public static ChannelInboundHandlerAdapter adaptWebSocketToNativeSocket(final Channel nativeSocketChannel) {
        return new ChannelInboundHandlerAdapter() {
            @Override
            public void channelActive(final ChannelHandlerContext webSocketContext) {
                webSocketContext.writeAndFlush(Unpooled.EMPTY_BUFFER);
            }

            @Override
            public void channelInactive(final ChannelHandlerContext webSocketContext) {
                if (nativeSocketChannel.isActive()) {
                    if (log.isDebugEnabled()) {
                        log.debug("{} Native connection closed by {}", nativeSocketChannel, webSocketContext.channel());
                    }
                    nativeSocketChannel.writeAndFlush(Unpooled.EMPTY_BUFFER).addListener(ChannelFutureListener.CLOSE);
                }
            }

            @Override
            public void channelRead(final ChannelHandlerContext webSocketContext, final Object msg) {
                if (nativeSocketChannel.isActive()) {
                    if (msg instanceof BinaryWebSocketFrame) {
                        final ByteBuf buf = ((BinaryWebSocketFrame) msg).content();
                        if (log.isTraceEnabled()) {
                            log.trace("[WS]{} -> [SO]{}: {}", webSocketContext.channel(), nativeSocketChannel, ByteBufUtil.hexDump(buf));
                        }
                        nativeSocketChannel.writeAndFlush(buf);
                    } else if (msg instanceof CloseWebSocketFrame) {
                        if (log.isDebugEnabled()) {
                            final CloseWebSocketFrame close = (CloseWebSocketFrame) msg;
                            log.debug("{} Connection closed: {}({})", webSocketContext.channel(), close.reasonText(), close.statusCode());
                        }
                        webSocketContext.close();
                    } else {
                        throw new UnsupportedOperationException("Unexpect websocket message: " + msg);
                    }
                } else {
                    ReferenceCountUtil.release(msg);
                }
            }

            @Override
            public void exceptionCaught(final ChannelHandlerContext webSocketContext, final Throwable cause) {
                log.warn("{} Software caused connection abort: {}", webSocketContext.channel(), cause.getMessage());
                WebSocketUtils.internalErrorClose(webSocketContext, cause.getMessage());
            }
        };
    }

    public static void main(String[] args) throws Exception {
        final ChannelFuture future = WebSocketForwarder.forwardToWebSocket(
                URI.create("ws://127.0.0.1:8080/ws/echo"), null,
                // URI.create("ws://127.0.0.1:2345/tunnel?id=WEBSOCKET-TEST"), "PASSIVE",
                URI.create("ws://127.0.0.1:8080/ws/print"), null
        );
        /*
        final ChannelFuture future = WebSocketForwarder.pipeToNativeSocket(
                // URI.create("ws://127.0.0.1:8080/ws/echo"), null,
                URI.create("ws://127.0.0.1:2345/tunnel?id=SOCKET-TEST"), "SOCKET-PASSIVE",
                // URI.create("ws://127.0.0.1:8080/ws/echo"), null,
                URI.create("ws://139.196.88.115:22")
        );
        */
        future.await();
        System.out.println("Wait over");
    }
}

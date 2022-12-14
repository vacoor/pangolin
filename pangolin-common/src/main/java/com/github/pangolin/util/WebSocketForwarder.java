package com.github.pangolin.util;

import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.MessageToMessageDecoder;
import io.netty.handler.codec.http.DefaultHttpHeaders;
import io.netty.handler.codec.http.HttpClientCodec;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.websocketx.*;
import io.netty.handler.codec.http.websocketx.extensions.compression.WebSocketClientCompressionHandler;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import io.netty.util.ReferenceCountUtil;
import io.netty.util.concurrent.DefaultThreadFactory;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.GenericFutureListener;
import lombok.extern.slf4j.Slf4j;

import javax.net.ssl.SSLException;
import java.net.URI;
import java.util.List;

/**
 * WebSocket 数据转发.
 */
@Slf4j
public class WebSocketForwarder {

    /**
     * 最大 HTTP 内容长度.
     */
    private static final int MAX_HTTP_CONTENT_LENGTH = 1024 * 1024 * 8;

    /**
     * 连接两个 WebSocket 服务并转发数据.
     *
     * @param webSocketEndpoint1 websocket 1
     * @param webSocketProtocol1 websocket 1 子协议
     * @param webSocketEndpoint2 websocket 2
     * @param webSocketProtocol2 websocket 2 子协议
     * @return
     * @throws SSLException
     */
    public static Channel forwardToWebSocket2(final String id,
                                              final URI webSocketEndpoint1, final String webSocketProtocol1,
                                              final URI webSocketEndpoint2, final String webSocketProtocol2) throws SSLException, InterruptedException {
        return openWebSocketChannel(webSocketEndpoint1, webSocketProtocol1, new WebSocketForwardingHandler() {
            @Override
            protected void forwarding(final ChannelHandlerContext webSocketContext1) throws Exception {
                webSocketContext1.channel().config().setAutoRead(false);

                openWebSocketChannel(webSocketEndpoint2, webSocketProtocol2, new WebSocketForwardingHandler() {
                    @Override
                    protected void forwarding(final ChannelHandlerContext webSocketContext2) {
                        webSocketContext2.channel().config().setAutoRead(false);

                        webSocketContext2.pipeline().remove(webSocketContext2.handler());
                        webSocketContext1.pipeline().remove(webSocketContext1.handler());

                        webSocketContext2.pipeline().addLast(pipeWebSocket(webSocketContext1));
                        webSocketContext1.pipeline().addLast(pipeWebSocket(webSocketContext2));

                        webSocketContext2.channel().config().setAutoRead(true);
                        webSocketContext1.channel().config().setAutoRead(true);
                    }
                }).closeFuture().addListener(new GenericFutureListener<Future<? super Void>>() {
                    @Override
                    public void operationComplete(final Future<? super Void> future) throws Exception {
                        if (webSocketContext1.channel().isActive()) {
                            WebSocketUtils.policyViolationClose(webSocketContext1, "Destination unavailable");
                        }
                    }
                });
            }
        });
    }


    public static Channel forwardToNativeSocket2(final String id,
                                                 final URI webSocketEndpoint1, final String webSocketProtocol1,
                                                 final URI nativeSocketEndpoint) throws SSLException, InterruptedException {
        return openWebSocketChannel(webSocketEndpoint1, webSocketProtocol1, new WebSocketForwardingHandler() {
            @Override
            protected void forwarding(final ChannelHandlerContext webSocketContext1) throws Exception {
                webSocketContext1.channel().config().setAutoRead(false);

                final EventLoopGroup nativeSocketGroup = new NioEventLoopGroup(1, new DefaultThreadFactory("WebSocket-PIPE-SLAVE", true));
                openSocketChannel(nativeSocketEndpoint.getHost(), nativeSocketEndpoint.getPort(), nativeSocketGroup, new ChannelInboundHandlerAdapter() {
                    @Override
                    public void channelRegistered(final ChannelHandlerContext nativeSocketContext) {
                        nativeSocketContext.channel().config().setAutoRead(false);

                        nativeSocketContext.pipeline().remove(nativeSocketContext.handler());
                        webSocketContext1.pipeline().remove(webSocketContext1.handler());

                        nativeSocketContext.pipeline().addLast(adaptSocketToWebSocket(webSocketContext1));
                        webSocketContext1.pipeline().addLast(adaptWebSocketToSocket(nativeSocketContext));

                        nativeSocketContext.channel().config().setAutoRead(true);
                        webSocketContext1.channel().config().setAutoRead(true);
                    }

                    @Override
                    public void channelRead(final ChannelHandlerContext nativeSocketContext, final Object msg) {
                        log.warn("{} Software caused forwarding abort: {}", nativeSocketContext.channel(), msg);
                        nativeSocketContext.close();
                    }

                    @Override
                    public void exceptionCaught(final ChannelHandlerContext nativeSocketContext, final Throwable cause) {
                        log.warn("{} Software caused forwarding abort: {}", nativeSocketContext.channel(), cause.getMessage());
                        nativeSocketContext.close();
                    }
                }).closeFuture().addListener(new GenericFutureListener<Future<? super Void>>() {
                    @Override
                    public void operationComplete(final Future<? super Void> future) throws Exception {
                        if (webSocketContext1.channel().isActive()) {
                            WebSocketUtils.policyViolationClose(webSocketContext1, "Destination unavailable");
                        }
                    }
                });
            }
        });
    }

    public static Channel openWebSocketChannel(final URI webSocketEndpoint, final String webSocketProtocol, final ChannelHandler... webSocketHandlers) throws SSLException, InterruptedException {
        final boolean isSecure = "wss".equalsIgnoreCase(webSocketEndpoint.getScheme());
        final SslContext context = isSecure ? createSslContext() : null;
        final int portToUse = 0 < webSocketEndpoint.getPort() ? webSocketEndpoint.getPort() : (isSecure ? 443 : 80);

        final EventLoopGroup webSocketGroup = new NioEventLoopGroup(1, new DefaultThreadFactory("WebSocket-PIPE-MASTER", true));
        return openSocketChannel(webSocketEndpoint.getHost(), portToUse, webSocketGroup, new ChannelInitializer<SocketChannel>() {
            @Override
            protected void initChannel(final SocketChannel ch) {
                final ChannelPipeline cp = ch.pipeline();
                if (null != context) {
                    cp.addLast(context.newHandler(ch.alloc()));
                }
                cp.addLast(new HttpClientCodec(), new HttpObjectAggregator(MAX_HTTP_CONTENT_LENGTH));
                cp.addLast(WebSocketClientCompressionHandler.INSTANCE);
                cp.addLast(new WebSocketClientProtocolHandler(WebSocketClientHandshakerFactory.newHandshaker(
                        webSocketEndpoint, WebSocketVersion.V13, webSocketProtocol, true, new DefaultHttpHeaders(), 65536, false, true
                ), false));
                cp.addLast(webSocketHandlers);
            }
        });
    }

    private static SslContext createSslContext() throws SSLException {
        return SslContextBuilder.forClient().trustManager(InsecureTrustManagerFactory.INSTANCE).build();
    }


    private static Channel openSocketChannel(final String host, final int port, final EventLoopGroup group, final ChannelHandler initializer) throws InterruptedException {
        Channel channel = null;
        try {
            final Bootstrap b = new Bootstrap();
            b.option(ChannelOption.TCP_NODELAY, true);
            b.option(ChannelOption.SO_KEEPALIVE, true);
            b.option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 5000);
            b.group(group).channel(NioSocketChannel.class).handler(initializer);
            channel = b.connect(host, port).sync().channel();
        } finally {
            if (null != channel) {
                channel.closeFuture().addListener(new ChannelFutureListener() {
                    @Override
                    public void operationComplete(final ChannelFuture future) {
                        group.shutdownGracefully();
                    }
                });
            } else {
                group.shutdownGracefully();
            }
        }
        return channel;
    }

    /**
     * WebSocket 转发处理器.
     */
    private abstract static class WebSocketForwardingHandler extends ChannelInboundHandlerAdapter {

        @Override
        public void userEventTriggered(final ChannelHandlerContext webSocketContext, final Object evt) throws Exception {
            // if (WebSocketClientProtocolHandler.ClientHandshakeStateEvent.HANDSHAKE_COMPLETE.equals(evt)) {
            if (WebSocketClientProtocolHandler.ClientHandshakeStateEvent.HANDSHAKE_ISSUED.equals(evt)) {
                this.forwarding(webSocketContext);
            }
        }

        /**
         * 初始化转发.
         *
         * @param webSocketContext channel 上下文
         * @throws Exception 如果发生错误
         */
        protected abstract void forwarding(final ChannelHandlerContext webSocketContext) throws Exception;

        @Override
        public void channelRead(final ChannelHandlerContext webSocketContext, final Object msg) throws Exception {
            /*-
             * 转发不应该走到这里, 走到这里说明转发逻辑存在问题.
             */
            log.warn("{} Software caused forwarding abort: {}", webSocketContext.channel(), msg);
            WebSocketUtils.internalErrorClose(webSocketContext, "SOFTWARE_FORWARDING_ABORT");
        }

        @Override
        public void exceptionCaught(final ChannelHandlerContext webSocketContext, final Throwable cause) throws Exception {
            log.warn("{} Software caused connection abort: {}", webSocketContext.channel(), cause.getMessage());
            WebSocketUtils.internalErrorClose(webSocketContext, cause.getMessage());
        }
    }

    /**
     * 创建连接 WebSocket channel 的适配器.
     *
     * @param targetWebSocketContext 输出源
     * @return 输入处理适配器
     */
    public static ChannelInboundHandlerAdapter pipeWebSocket(final ChannelHandlerContext targetWebSocketContext) {
        return new ChannelInboundHandlerAdapter() {
            @Override
            public void channelActive(final ChannelHandlerContext sourceWebSocketContext) {
                sourceWebSocketContext.writeAndFlush(Unpooled.EMPTY_BUFFER);
            }

            @Override
            public void channelInactive(final ChannelHandlerContext sourceWebSocketContext) {
                if (targetWebSocketContext.channel().isActive()) {
                    // 非正常关闭, 另一侧可能没有关闭.
                    log.warn("{} WebSocket PIPE the input has been closed, the output will be closed: {}", sourceWebSocketContext.channel(), targetWebSocketContext.channel());
                    targetWebSocketContext.writeAndFlush(Unpooled.EMPTY_BUFFER).addListener(ChannelFutureListener.CLOSE);
                }
            }

            @Override
            public void channelRead(final ChannelHandlerContext sourceWebSocketContext, final Object msg) {
                if (targetWebSocketContext.channel().isActive()) {
                    targetWebSocketContext.writeAndFlush(msg);
                } else {
                    ReferenceCountUtil.release(msg);
                }

                /*-
                 * 对于 CloseWebSocketFrame:
                 * 服务端无法收到 @see io.netty.handler.codec.http.websocketx.WebSocketServerProtocolHandler.decode
                 * 客户端需要设置 @see io.netty.handler.codec.http.websocketx.WebSocketClientProtocolHandler#handleCloseFrames
                 * 这里通过 #handlerAdded 来处理
                 */
                if (msg instanceof CloseWebSocketFrame) {
                    handleCloseFrame((CloseWebSocketFrame) msg, sourceWebSocketContext, targetWebSocketContext);
                }
            }

            private void handleCloseFrame(final CloseWebSocketFrame c,
                                          final ChannelHandlerContext sourceWebSocketContext,
                                          final ChannelHandlerContext targetWebSocketContext) {
                c.retain();
                log.info("{} WebSocket connection closed by {}/{}", sourceWebSocketContext.channel(), c.statusCode(), c.reasonText());
                if (targetWebSocketContext.channel().isActive()) {
                    targetWebSocketContext.writeAndFlush(c).addListener(ChannelFutureListener.CLOSE);
                } else {
                    c.release();
                }
                sourceWebSocketContext.writeAndFlush(Unpooled.EMPTY_BUFFER).addListener(ChannelFutureListener.CLOSE);
            }

            @Override
            public void handlerAdded(final ChannelHandlerContext webSocketContext) throws Exception {
                final ChannelPipeline cp = webSocketContext.pipeline();
                final ChannelHandlerContext context = cp.context(WebSocketServerProtocolHandler.class);
                final ChannelHandlerContext contextToUse = null != context ? context : cp.context(WebSocketClientProtocolHandler.class);
                if (null != contextToUse && null == cp.get("WsCloser")) {
                    cp.addBefore(contextToUse.name(), "WsCloser", new MessageToMessageDecoder<CloseWebSocketFrame>() {
                        @Override
                        protected void decode(final ChannelHandlerContext ctx, final CloseWebSocketFrame c, final List<Object> out) throws Exception {
                            handleCloseFrame(c, ctx, targetWebSocketContext);
                        }
                    });
                }
            }

            @Override
            public void exceptionCaught(final ChannelHandlerContext sourceWebSocketContext, final Throwable cause) {
                log.warn("{} Software caused connection abort: {}, -> {}", sourceWebSocketContext.channel(), cause.getMessage(), targetWebSocketContext.channel(), cause);
                WebSocketUtils.internalErrorClose(sourceWebSocketContext, cause.getMessage());
                WebSocketUtils.internalErrorClose(targetWebSocketContext, cause.getMessage());
            }
        };
    }

    /**
     * 将原生 Socket 适配到 WebSocket.
     *
     * @param webSocketContext 输出源
     * @return 输入处理适配器
     */
    public static ChannelInboundHandlerAdapter adaptSocketToWebSocket(final ChannelHandlerContext webSocketContext) {
        return new ChannelInboundHandlerAdapter() {
            @Override
            public void channelActive(final ChannelHandlerContext nativeSocketContext) {
                nativeSocketContext.writeAndFlush(Unpooled.EMPTY_BUFFER);
            }

            @Override
            public void channelInactive(final ChannelHandlerContext nativeSocketContext) {
                if (webSocketContext.channel().isActive()) {
                    log.debug("{} Socket-WebSocket PIPE the input has been closed, the output will be closed: {}", nativeSocketContext.channel(), webSocketContext.channel());
                    WebSocketUtils.goingAwayClose(webSocketContext, "Connection closed");
                }
            }

            @Override
            public void channelRead(final ChannelHandlerContext nativeSocketContext, final Object msg) throws Exception {
                if (webSocketContext.channel().isActive()) {
                    if (msg instanceof ByteBuf) {
                        webSocketContext.writeAndFlush(new BinaryWebSocketFrame((ByteBuf) msg));
                    } else {
                        throw new UnsupportedOperationException("Unexpect socket message: " + msg);
                    }
                } else {
                    ReferenceCountUtil.release(msg);
                }
            }

            @Override
            public void exceptionCaught(final ChannelHandlerContext nativeSocketContext, final Throwable cause) throws Exception {
                log.warn("{} Software caused connection abort: {}, -> {}", nativeSocketContext.channel(), cause.getMessage(), webSocketContext.channel());
                nativeSocketContext.close();
                WebSocketUtils.internalErrorClose(webSocketContext, cause.getMessage());
            }
        };
    }

    /**
     * 将 WebSocket 适配到原生 Socket.
     *
     * @param nativeSocketContext 原生 socket
     * @return 适配器
     */
    public static ChannelInboundHandlerAdapter adaptWebSocketToSocket(final ChannelHandlerContext nativeSocketContext) {
        return new ChannelInboundHandlerAdapter() {

            @Override
            public void channelActive(final ChannelHandlerContext webSocketContext) {
                webSocketContext.writeAndFlush(Unpooled.EMPTY_BUFFER);
            }

            @Override
            public void channelInactive(final ChannelHandlerContext webSocketContext) {
                if (nativeSocketContext.channel().isActive()) {
                    log.info("{} WebSocket-Socket PIPE the input has been closed, the output will be closed: {}", webSocketContext.channel(), nativeSocketContext.channel());
                    nativeSocketContext.writeAndFlush(Unpooled.EMPTY_BUFFER).addListener(ChannelFutureListener.CLOSE);
                }
            }

            @Override
            public void channelRead(final ChannelHandlerContext webSocketContext, final Object msg) {
                if (nativeSocketContext.channel().isActive()) {
                    if (msg instanceof BinaryWebSocketFrame) {
                        final ByteBuf buf = ((BinaryWebSocketFrame) msg).content();
                        nativeSocketContext.writeAndFlush(buf);
                    } else if (msg instanceof CloseWebSocketFrame) {
                        final CloseWebSocketFrame c = (CloseWebSocketFrame) msg;
                        log.info("{} WebSocket connection closed by {}/{}", webSocketContext.channel(), c.statusCode(), c.reasonText());
                        try {
                            webSocketContext.writeAndFlush(Unpooled.EMPTY_BUFFER).addListener(ChannelFutureListener.CLOSE);
                            nativeSocketContext.writeAndFlush(Unpooled.EMPTY_BUFFER).addListener(ChannelFutureListener.CLOSE);
                        } finally {
                            c.release();
                        }
                    } else {
                        throw new UnsupportedOperationException("Unexpect websocket message: " + msg);
                    }
                } else {
                    ReferenceCountUtil.release(msg);
                }
            }

            @Override
            public void exceptionCaught(final ChannelHandlerContext webSocketContext, final Throwable cause) {
                log.warn("{} Software caused connection abort: {}, -> {}", webSocketContext.channel(), cause.getMessage(), nativeSocketContext.channel(), cause);

                WebSocketUtils.internalErrorClose(webSocketContext, cause.getMessage());
                nativeSocketContext.close();
            }
        };
    }

    public static void main(String[] args) throws Exception {
        final Channel future = WebSocketForwarder.forwardToWebSocket2("1",
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
        future.closeFuture().await();
        System.out.println("Wait over");
    }
}

package com.github.pangolin.util;

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
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.http.DefaultHttpHeaders;
import io.netty.handler.codec.http.HttpClientCodec;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.websocketx.BinaryWebSocketFrame;
import io.netty.handler.codec.http.websocketx.CloseWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketClientHandshakerFactory;
import io.netty.handler.codec.http.websocketx.WebSocketClientProtocolHandler;
import io.netty.handler.codec.http.websocketx.WebSocketVersion;
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
    public static ChannelFuture forwardToWebSocket2(final String id,
                                                    final URI webSocketEndpoint1, final String webSocketProtocol1,
                                                    final URI webSocketEndpoint2, final String webSocketProtocol2) throws SSLException, InterruptedException {
        return openWebSocket(webSocketEndpoint1, webSocketProtocol1, new WebSocketForwardingHandler() {
            @Override
            protected void forwarding(final ChannelHandlerContext webSocketContext1) throws Exception {
                webSocketContext1.channel().config().setAutoRead(false);

                openWebSocket(webSocketEndpoint2, webSocketProtocol2, new WebSocketForwardingHandler() {
                    @Override
                    protected void forwarding(final ChannelHandlerContext webSocketContext2) {
                        webSocketContext2.channel().config().setAutoRead(false);

                        webSocketContext2.pipeline().remove(webSocketContext2.handler());
                        webSocketContext1.pipeline().remove(webSocketContext1.handler());

                        webSocketContext2.pipeline().addLast(pipe(webSocketContext1));
                        webSocketContext1.pipeline().addLast(pipe(webSocketContext2));

                        webSocketContext2.channel().config().setAutoRead(true);
                        webSocketContext1.channel().config().setAutoRead(true);
                    }
                }).channel().closeFuture().addListener(new GenericFutureListener<Future<? super Void>>() {
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


    public static ChannelFuture forwardToNativeSocket2(final String id,
                                                       final URI webSocketEndpoint1, final String webSocketProtocol1,
                                                       final URI nativeSocketEndpoint) throws SSLException, InterruptedException {
        return openWebSocket(webSocketEndpoint1, webSocketProtocol1, new WebSocketForwardingHandler() {
            @Override
            protected void forwarding(final ChannelHandlerContext webSocketContext1) throws Exception {
                webSocketContext1.channel().config().setAutoRead(false);

                final EventLoopGroup nativeSocketGroup = new NioEventLoopGroup(1, new DefaultThreadFactory("WebSocket-PIPE-SLAVE", true));
                launch(nativeSocketEndpoint.getHost(), nativeSocketEndpoint.getPort(), nativeSocketGroup, new ChannelInboundHandlerAdapter() {
                    @Override
                    public void channelRegistered(final ChannelHandlerContext nativeSocketContext) {
                        nativeSocketContext.channel().config().setAutoRead(false);

                        nativeSocketContext.pipeline().remove(nativeSocketContext.handler());
                        webSocketContext1.pipeline().remove(webSocketContext1.handler());

                        nativeSocketContext.pipeline().addLast(adaptNativeSocketToWebSocket(webSocketContext1.channel()));
                        webSocketContext1.pipeline().addLast(adaptWebSocketToNativeSocket(nativeSocketContext.channel()));

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
                }).channel().closeFuture().addListener(new GenericFutureListener<Future<? super Void>>() {
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

    public static ChannelFuture openWebSocket(final URI webSocketEndpoint, final String webSocketProtocol, final ChannelHandler... webSocketHandlers) throws SSLException, InterruptedException {
        final boolean isSecure = "wss".equalsIgnoreCase(webSocketEndpoint.getScheme());
        final SslContext context = isSecure ? createSslContext() : null;
        final int portToUse = 0 < webSocketEndpoint.getPort() ? webSocketEndpoint.getPort() : (isSecure ? 443 : 80);

        final EventLoopGroup webSocketGroup = new NioEventLoopGroup(1, new DefaultThreadFactory("WebSocket-PIPE-MASTER", true));
        return launch(webSocketEndpoint.getHost(), portToUse, webSocketGroup, new ChannelInitializer<SocketChannel>() {
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

    public static SslContext createSslContext() throws SSLException {
        return SslContextBuilder.forClient().trustManager(InsecureTrustManagerFactory.INSTANCE).build();
    }


    private static ChannelFuture launch(final String host, final int port, final EventLoopGroup group, final ChannelHandler initializer) {
        ChannelFuture channelFuture = null;
        try {
            final Bootstrap b = new Bootstrap();
            b.option(ChannelOption.TCP_NODELAY, true);
            b.option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 5000);
            b.group(group).channel(NioSocketChannel.class).handler(initializer);
            channelFuture = b.connect(host, port);
        } finally {
            if (null != channelFuture) {
                channelFuture.channel().closeFuture().addListener(new ChannelFutureListener() {
                    @Override
                    public void operationComplete(final ChannelFuture future) {
                        group.shutdownGracefully();
                    }
                });
            } else {
                group.shutdownGracefully();
            }
        }
        return channelFuture;
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

    public static ChannelInboundHandlerAdapter pipe(final ChannelHandlerContext target) {
        return new ChannelInboundHandlerAdapter() {
            @Override
            public void channelActive(final ChannelHandlerContext sourceContext) {
                sourceContext.writeAndFlush(Unpooled.EMPTY_BUFFER);
            }

            @Override
            public void channelInactive(final ChannelHandlerContext sourceContext) {
                if (target.channel().isActive()) {
                    target.writeAndFlush(Unpooled.EMPTY_BUFFER).addListener(ChannelFutureListener.CLOSE);
                }
            }

            @Override
            public void channelRead(final ChannelHandlerContext sourceContext, final Object msg) {
                if (target.channel().isActive()) {
                    target.writeAndFlush(msg);
                } else {
                    ReferenceCountUtil.release(msg);
                }
            }

            @Override
            public void exceptionCaught(final ChannelHandlerContext sourceContext, final Throwable cause) {
                log.error("Channel PIPE error: {}, {} -> {}", cause.getMessage(), sourceContext.channel(), target, cause);
                sourceContext.close();
            }
        };
    }

    /**
     * 将原生 Socket 适配到 WebSocket.
     *
     * @param webSocket webSocket
     * @return 适配器
     */
    public static ChannelInboundHandlerAdapter adaptNativeSocketToWebSocket(final Channel webSocket) {
        return new ChannelInboundHandlerAdapter() {
            @Override
            public void channelActive(final ChannelHandlerContext nativeSocketContext) {
                nativeSocketContext.writeAndFlush(Unpooled.EMPTY_BUFFER);
            }

            @Override
            public void channelInactive(final ChannelHandlerContext nativeSocket) {
                if (webSocket.isActive()) {
                    if (log.isDebugEnabled()) {
                        log.debug("{} WebSocket Connection closed by {}", webSocket, nativeSocket.channel());
                    }
                    webSocket.writeAndFlush(Unpooled.EMPTY_BUFFER).addListener(ChannelFutureListener.CLOSE);
                }
            }

            @Override
            public void channelRead(final ChannelHandlerContext nativeSocket, final Object msg) throws Exception {
                if (webSocket.isActive()) {
                    if (msg instanceof ByteBuf) {
                        if (log.isTraceEnabled()) {
                            log.trace("[SO]{} -> [WS]{}: {}", nativeSocket.channel(), webSocket, ByteBufUtil.hexDump(((ByteBuf) msg)));
                        }
                        webSocket.writeAndFlush(new BinaryWebSocketFrame((ByteBuf) msg));
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
     * @param nativeSocket 原生 socket
     * @return 适配器
     */
    public static ChannelInboundHandlerAdapter adaptWebSocketToNativeSocket(final Channel nativeSocket) {
        return new ChannelInboundHandlerAdapter() {
            @Override
            public void channelActive(final ChannelHandlerContext webSocket) {
                webSocket.writeAndFlush(Unpooled.EMPTY_BUFFER);
            }

            @Override
            public void channelInactive(final ChannelHandlerContext webSocket) {
                if (nativeSocket.isActive()) {
                    if (log.isDebugEnabled()) {
                        log.debug("{} Native connection closed by {}", nativeSocket, webSocket.channel());
                    }
                    nativeSocket.writeAndFlush(Unpooled.EMPTY_BUFFER).addListener(ChannelFutureListener.CLOSE);
                }
            }

            @Override
            public void channelRead(final ChannelHandlerContext webSocket, final Object msg) {
                if (nativeSocket.isActive()) {
                    if (msg instanceof BinaryWebSocketFrame) {
                        final ByteBuf buf = ((BinaryWebSocketFrame) msg).content();
                        if (log.isTraceEnabled()) {
                            log.trace("[WS]{} -> [SO]{}: {}", webSocket.channel(), nativeSocket, ByteBufUtil.hexDump(buf));
                        }
                        nativeSocket.writeAndFlush(buf);
                    } else if (msg instanceof CloseWebSocketFrame) {
                        if (log.isDebugEnabled()) {
                            final CloseWebSocketFrame close = (CloseWebSocketFrame) msg;
                            log.debug("{} Connection closed: {}({})", webSocket.channel(), close.reasonText(), close.statusCode());
                        }
                        webSocket.close();
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
        final ChannelFuture future = WebSocketForwarder.forwardToWebSocket2("1",
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
        future.channel().closeFuture().await();
        System.out.println("Wait over");
    }
}

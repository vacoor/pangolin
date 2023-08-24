package com.github.pangolin.util;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.http.DefaultHttpHeaders;
import io.netty.handler.codec.http.HttpClientCodec;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.websocketx.*;
import io.netty.handler.codec.http.websocketx.extensions.compression.WebSocketClientCompressionHandler;
import io.netty.handler.flow.FlowControlHandler;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import io.netty.handler.timeout.IdleStateEvent;
import io.netty.handler.timeout.IdleStateHandler;
import io.netty.util.concurrent.DefaultThreadFactory;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.GenericFutureListener;
import lombok.extern.slf4j.Slf4j;

import javax.net.ssl.SSLException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.URI;

;

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
        return openWebSocketChannel(webSocketEndpoint1, webSocketProtocol1, new WebSocketHandshakedHandler() {
            @Override
            protected void channelHandshaked(final ChannelHandlerContext webSocketContext1) throws Exception {
                webSocketContext1.channel().config().setAutoRead(false);

                openWebSocketChannel(webSocketEndpoint2, webSocketProtocol2, new WebSocketHandshakedHandler() {
                    @Override
                    protected void channelHandshaked(final ChannelHandlerContext webSocketContext2) {
                        webSocketContext2.channel().config().setAutoRead(false);

                        webSocketContext2.pipeline().replace(webSocketContext2.name(), null, pipeWebSocket(webSocketContext1));
                        webSocketContext1.pipeline().replace(webSocketContext1.name(), null, pipeWebSocket(webSocketContext2));

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

    public static ChannelFuture br(final SocketAddress upstream, final WebSocketClientHandshaker downstream, final EventLoopGroup brGroup) throws InterruptedException {
        return Channels.open(upstream, false, brGroup, new ChannelInboundHandlerAdapter() {
            @Override
            public void channelActive(final ChannelHandlerContext upstreamCtx) throws Exception {
                openWs(downstream, brGroup, new ChannelInboundHandlerAdapter() {
                    @Override
                    public void handlerAdded(final ChannelHandlerContext downstreamCtx) throws Exception {
                        final ChannelPipeline cp = downstreamCtx.pipeline();
                        if (null == cp.get(FlowControlHandler.class)) {
                            final ChannelHandlerContext wsCtx = cp.context(WebSocketClientProtocolHandler.class);
                            cp.addBefore(wsCtx.name(), FlowControlHandler.class.getName(), new FlowControlHandler());
                        }
                    }

                    @Override
                    public void userEventTriggered(final ChannelHandlerContext downstreamCtx, final Object evt) throws Exception {
                        if (WebSocketClientProtocolHandler.ClientHandshakeStateEvent.HANDSHAKE_COMPLETE.equals(evt)) {
                            downstreamCtx.channel().config().setAutoRead(false);

                            upstreamCtx.pipeline().replace(upstreamCtx.name(), "upstream-br", Redirects.socketRedirectToWebSocket(downstreamCtx));
                            downstreamCtx.pipeline().replace(downstreamCtx.name(), "downstream-br", Redirects.webSocketRedirectToSocket(upstreamCtx));

                            upstreamCtx.channel().config().setAutoRead(true);
                            downstreamCtx.channel().config().setAutoRead(true);
                        }
                    }
                }).addListener(new ChannelFutureListener() {
                    @Override
                    public void operationComplete(final ChannelFuture future) throws Exception {
                        if (!future.isSuccess()) {
                            future.channel().close();
                            upstreamCtx.channel().close();
                        }
                    }
                });
            }
        }).addListener(ChannelFutureListener.CLOSE_ON_FAILURE);
    }

    public static ChannelFuture br(final WebSocketClientHandshaker upstream, final WebSocketClientHandshaker downstream, final EventLoopGroup brGroup) throws InterruptedException, SSLException {
        return openWs(upstream, brGroup, new ChannelInboundHandlerAdapter() {

            @Override
            public void handlerAdded(final ChannelHandlerContext upstreamCtx) throws Exception {
                final ChannelPipeline cp = upstreamCtx.pipeline();
                if (null == cp.get(FlowControlHandler.class)) {
                    final ChannelHandlerContext wsCtx = cp.context(WebSocketClientProtocolHandler.class);
                    cp.addBefore(wsCtx.name(), FlowControlHandler.class.getName(), new FlowControlHandler());
                }
            }

            @Override
            public void userEventTriggered(final ChannelHandlerContext upstreamCtx, final Object evt) throws Exception {
                if (WebSocketClientProtocolHandler.ClientHandshakeStateEvent.HANDSHAKE_COMPLETE.equals(evt)) {
                    upstreamCtx.channel().config().setAutoRead(false);
                    openWs(downstream, brGroup, new ChannelInboundHandlerAdapter() {
                        @Override
                        public void handlerAdded(final ChannelHandlerContext downstreamCtx) throws Exception {
                            final ChannelPipeline cp = downstreamCtx.pipeline();
                            if (null == cp.get(FlowControlHandler.class)) {
                                final ChannelHandlerContext wsCtx = cp.context(WebSocketClientProtocolHandler.class);
                                cp.addBefore(wsCtx.name(), FlowControlHandler.class.getName(), new FlowControlHandler());
                            }
                        }

                        @Override
                        public void userEventTriggered(final ChannelHandlerContext downstreamCtx, final Object evt) throws Exception {
                            if (WebSocketClientProtocolHandler.ClientHandshakeStateEvent.HANDSHAKE_COMPLETE.equals(evt)) {
                                downstreamCtx.channel().config().setAutoRead(false);

                                upstreamCtx.pipeline().replace(upstreamCtx.name(), "upstream-br", Redirects.webSocketRedirectToWebSocket(downstreamCtx));
                                downstreamCtx.pipeline().replace(downstreamCtx.name(), "downstream-br", Redirects.webSocketRedirectToWebSocket(upstreamCtx));

                                upstreamCtx.channel().config().setAutoRead(true);
                                downstreamCtx.channel().config().setAutoRead(true);
                            }
                        }
                    }).addListener(new ChannelFutureListener() {
                        @Override
                        public void operationComplete(final ChannelFuture future) throws Exception {
                            if (!future.isSuccess()) {
                                future.channel().close();
                                upstreamCtx.channel().close();
                            }
                        }
                    });
                }
            }
        }).addListener(ChannelFutureListener.CLOSE_ON_FAILURE);
    }

    private static ChannelFuture openWs(final WebSocketClientHandshaker handshaker,
                                        final EventLoopGroup group, final ChannelHandler... wsHandlers) throws InterruptedException, SSLException {
        final URI webSocketEndpoint = handshaker.uri();
        final InetSocketAddress remoteAddress = new InetSocketAddress(webSocketEndpoint.getHost(), webSocketEndpoint.getPort());
        final boolean isSecure = "wss".equalsIgnoreCase(webSocketEndpoint.getScheme());
        final SslContext sslContext = isSecure ? createClientSslContext() : null;

        return Channels.open(remoteAddress, null, true, group, new ChannelInitializer<SocketChannel>() {

            @Override
            protected void initChannel(final SocketChannel ch) throws Exception {
                final ChannelPipeline cp = ch.pipeline();
                if (null != sslContext) {
                    cp.addLast(sslContext.newHandler(ch.alloc()));
                }
                cp.addLast(new HttpClientCodec());
                cp.addLast(new HttpObjectAggregator(1024 * 1024 * 8));
                cp.addLast(new WebSocketClientProtocolHandler(handshaker));
                cp.addLast(wsHandlers);
            }

        });
    }


    public static Channel forwardToNativeSocket2(final String id,
                                                 final URI webSocketEndpoint1, final String webSocketProtocol1,
                                                 final URI nativeSocketEndpoint) throws SSLException, InterruptedException {
        return openWebSocketChannel(webSocketEndpoint1, webSocketProtocol1, new WebSocketHandshakedHandler() {
            @Override
            protected void channelHandshaked(final ChannelHandlerContext webSocketContext1) throws Exception {
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
        final SslContext context = isSecure ? createClientSslContext() : null;
        final int portToUse = 0 < webSocketEndpoint.getPort() ? webSocketEndpoint.getPort() : (isSecure ? 443 : 80);

        final EventLoopGroup webSocketGroup = new NioEventLoopGroup(1, new DefaultThreadFactory("WebSocket-PIPE-MASTER", true));
        return openSocketChannel(webSocketEndpoint.getHost(), portToUse, webSocketGroup, new ChannelInitializer<SocketChannel>() {
            @Override
            protected void initChannel(final SocketChannel ch) {
                final ChannelPipeline cp = ch.pipeline();
                if (null != context) {
                    cp.addLast(context.newHandler(ch.alloc()));
                }
                cp.addLast(new IdleStateHandler(0, 0, 50));
                cp.addLast(new HttpClientCodec(), new HttpObjectAggregator(MAX_HTTP_CONTENT_LENGTH));
                cp.addLast(WebSocketClientCompressionHandler.INSTANCE);
                cp.addLast(new WebSocketClientProtocolHandler(WebSocketClientHandshakerFactory.newHandshaker(
                        webSocketEndpoint, WebSocketVersion.V13, webSocketProtocol, true, new DefaultHttpHeaders(), 65536, true, true
                ), false));
                cp.addLast(webSocketHandlers);
            }
        });
    }

    private static SslContext createClientSslContext() throws SSLException {
        return SslContextBuilder.forClient().trustManager(InsecureTrustManagerFactory.INSTANCE).build();
    }


    public static Channel openSocketChannel(final String host, final int port, final EventLoopGroup group, final ChannelHandler initializer) throws InterruptedException {
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
    private abstract static class WebSocketHandshakedHandler extends ChannelInboundHandlerAdapter {

        @Override
        public void userEventTriggered(final ChannelHandlerContext webSocketContext, final Object evt) throws Exception {
            // if (WebSocketClientProtocolHandler.ClientHandshakeStateEvent.HANDSHAKE_COMPLETE.equals(evt)) {
            if (WebSocketClientProtocolHandler.ClientHandshakeStateEvent.HANDSHAKE_ISSUED.equals(evt)) {
                this.channelHandshaked(webSocketContext);
            } else if (evt instanceof IdleStateEvent) {
                webSocketContext.writeAndFlush(new PingWebSocketFrame());
            }
        }

        /**
         * 初始化转发.
         *
         * @param webSocketContext channel 上下文
         * @throws Exception 如果发生错误
         */
        protected abstract void channelHandshaked(final ChannelHandlerContext webSocketContext) throws Exception;

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
    public static ChannelInboundHandler pipeWebSocket(final ChannelHandlerContext targetWebSocketContext) {
        return Redirects.webSocketRedirectToWebSocket(targetWebSocketContext);
    }

    /**
     * 将原生 Socket 适配到 WebSocket.
     *
     * @param webSocketContext 输出源
     * @return 输入处理适配器
     */
    public static ChannelInboundHandler adaptSocketToWebSocket(final ChannelHandlerContext webSocketContext) {
        return Redirects.socketRedirectToWebSocket(webSocketContext);
    }

    /**
     * 将 WebSocket 适配到原生 Socket.
     *
     * @param nativeSocketContext 原生 socket
     * @return 适配器
     */
    public static ChannelInboundHandler adaptWebSocketToSocket(final ChannelHandlerContext nativeSocketContext) {
        return Redirects.webSocketRedirectToSocket(nativeSocketContext);
    }

    public static void main(String[] args) throws Exception {
        final Channel future = WebSocketForwarder.forwardToWebSocket2("1",
                URI.create("ws://127.0.0.1:8080/ws/echo"), null,
                // URI.create("ws://127.0.0.1:2345/tunnel?id=WEBSOCKET-TEST"), "TUNNEL_RESPONSE",
                URI.create("ws://127.0.0.1:8080/ws/print"), null
        );
        /*
        final ChannelFuture future = WebSocketForwarder.pipeToNativeSocket(
                // URI.create("ws://127.0.0.1:8080/ws/echo"), null,
                URI.create("ws://127.0.0.1:2345/tunnel?id=SOCKET-TEST"), "SOCKET-TUNNEL_RESPONSE",
                // URI.create("ws://127.0.0.1:8080/ws/echo"), null,
                URI.create("ws://139.196.88.115:22")
        );
        */
        future.closeFuture().await();
        System.out.println("Wait over");
    }
}

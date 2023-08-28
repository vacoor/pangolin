package com.github.pangolin.proxy.server.websocket;

import com.github.pangolin.handler.SocketInboundRedirectHandler;
import com.github.pangolin.handler.SocketOverWebSocketDecodeHandler;
import com.github.pangolin.handler.SocketOverWebSocketEncodeHandler;
import com.github.pangolin.util.Channels;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.ChannelPromise;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.websocketx.Utf8FrameValidator;
import io.netty.handler.codec.http.websocketx.WebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketServerHandshaker;
import io.netty.handler.codec.http.websocketx.WebSocketServerHandshakerFactory;
import io.netty.handler.ssl.SslHandler;
import io.netty.util.AttributeKey;
import io.netty.util.ReferenceCountUtil;
import lombok.extern.slf4j.Slf4j;

import static io.netty.handler.codec.http.HttpMethod.CONNECT;
import static io.netty.handler.codec.http.HttpMethod.GET;
import static io.netty.handler.codec.http.HttpResponseStatus.FORBIDDEN;
import static io.netty.handler.codec.http.HttpUtil.isKeepAlive;

/**
 * @see io.netty.handler.codec.http.websocketx.WebSocketServerProtocolHandler
 */
@Slf4j
public class WebSocketProxyServerHandler extends ChannelInboundHandlerAdapter {
    private static final AttributeKey<WebSocketServerHandshaker> HANDSHAKER_ATTR_KEY = AttributeKey.valueOf(WebSocketServerHandshaker.class, "HANDSHAKER");

    private final EventLoopGroup proxyGroup;
    private final boolean allowExtensions;
    private final int maxFramePayloadSize;
    private final boolean allowMaskMismatch;

    public WebSocketProxyServerHandler(EventLoopGroup proxyGroup, boolean allowExtensions, int maxFrameSize, boolean allowMaskMismatch) {
        this.proxyGroup = proxyGroup;
        this.allowExtensions = allowExtensions;
        maxFramePayloadSize = maxFrameSize;
        this.allowMaskMismatch = allowMaskMismatch;
    }

    @Override
    public void handlerAdded(final ChannelHandlerContext ctx) throws Exception {
        /*-
         * WebSocketServerProtocolHandshakeHandler --> 403
         * Utf8FrameValidator
         * WebSocketServerProtocolHandler
         *   |- handle close frame
         *   |- exception caught
         * UdfHandler.
         */
        final ChannelPipeline cp = ctx.pipeline();
        /*
        ctx.pipeline().addAfter(ctx.name(), "DECODER", new MessageToMessageDecoder<WebSocketFrame>() {

            @Override
            protected void decode(final ChannelHandlerContext ctx, final WebSocketFrame frame, final List<Object> out) throws Exception {

                if (frame instanceof CloseWebSocketFrame) {
                    WebSocketServerHandshaker handshaker = getHandshaker(ctx.channel());
                    if (handshaker != null) {
                        frame.retain();
                        handshaker.close(ctx.channel(), (CloseWebSocketFrame) frame);
                    } else {
                        ctx.writeAndFlush(Unpooled.EMPTY_BUFFER).addListener(ChannelFutureListener.CLOSE);
                    }
                    return;
                }
                if (frame instanceof PingWebSocketFrame) {
                    frame.content().retain();
                    ctx.channel().writeAndFlush(new PongWebSocketFrame(frame.content()));
                    return;
                }
                if (frame instanceof PongWebSocketFrame) {
                    // Pong frames need to get ignored
                    return;
                }

                out.add(frame.retain());
            }

            @Override
            public void exceptionCaught(final ChannelHandlerContext ctx, final Throwable cause) throws Exception {
                if (cause instanceof WebSocketHandshakeException) {
                    FullHttpResponse response = new DefaultFullHttpResponse(HTTP_1_1, HttpResponseStatus.BAD_REQUEST, Unpooled.wrappedBuffer(cause.getMessage().getBytes()));
                    ctx.channel().writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
                } else {
                    ctx.fireExceptionCaught(cause);
                    ctx.close();
                }
            }
        });
        if (cp.get(Utf8FrameValidator.class) == null) {
            // Add the UFT8 checking after this one.
            ctx.pipeline().addAfter(ctx.name(), Utf8FrameValidator.class.getName(), new Utf8FrameValidator());
        }
        */
    }

    @Override
    public void channelRead(final ChannelHandlerContext ctx, Object msg) throws Exception {
        try {
            if (msg instanceof FullHttpRequest && ((FullHttpRequest) msg).decoderResult().isSuccess()) {
                final FullHttpRequest httpRequest = (FullHttpRequest) msg;
                if (GET.equals(httpRequest.method())) {
                    final WebSocketServerHandshakerFactory factory = new WebSocketServerHandshakerFactory(
                            getWebSocketLocation(ctx.pipeline(), httpRequest, ""),
                            ",CONNECT", allowExtensions, maxFramePayloadSize, allowMaskMismatch
                    );
                    final WebSocketServerHandshaker handshaker = factory.newHandshaker(httpRequest);
                    if (null != handshaker) {
                        final ChannelFuture handshakeFuture = handshake(ctx, httpRequest, handshaker, ctx.newPromise());
                        handshakeFuture.addListener(new ChannelFutureListener() {
                            @Override
                            public void operationComplete(final ChannelFuture future) throws Exception {
                                if (!future.isSuccess()) {
                                    ctx.fireExceptionCaught(future.cause());
                                }
                            }
                        });
                        ctx.channel().attr(HANDSHAKER_ATTR_KEY).set(handshaker);
//                        ctx.pipeline().replace(this, "WS403Responder", new HttpRequestForbiddenResponder());
                    } else {
                        WebSocketServerHandshakerFactory.sendUnsupportedVersionResponse(ctx.channel());
                    }
                } else {
                    ctx.writeAndFlush(newForbiddenResponse(httpRequest));
                }
            } else {
                Channels.closeOnFlush(ctx.channel());
            }
        } finally {
            ReferenceCountUtil.release(msg);
        }
    }

    private class WebSocketCtrlFrameHandler extends SimpleChannelInboundHandler<WebSocketFrame> {

        @Override
        protected void channelRead0(final ChannelHandlerContext ctx, final WebSocketFrame msg) throws Exception {
            // final WebSocketServerHandshaker handshaker = ctx.channel().attr(HANDSHAKER_ATTR_KEY).get();
            ctx.fireChannelRead(msg);
        }
    }

    private class HttpRequestForbiddenResponder extends ChannelInboundHandlerAdapter {
        @Override
        public void channelRead(final ChannelHandlerContext ctx, final Object msg) throws Exception {
            if (msg instanceof HttpRequest) {
                ReferenceCountUtil.release(msg);
                ctx.channel().writeAndFlush(newForbiddenResponse((HttpRequest) msg));
            } else {
                ctx.fireChannelRead(msg);
            }
        }
    }

    private FullHttpResponse newForbiddenResponse(final HttpRequest httpRequest) {
        return new DefaultFullHttpResponse(httpRequest.protocolVersion(), FORBIDDEN);
    }


    protected ChannelPromise handshake(final ChannelHandlerContext ctx, final FullHttpRequest req, final WebSocketServerHandshaker handshaker, final ChannelPromise promise) throws Exception {
        // final String s = handshaker.selectedSubprotocol();
        String s = req.headers().get(HttpHeaderNames.SEC_WEBSOCKET_PROTOCOL);
        if (CONNECT.name().equalsIgnoreCase(s)) {
            handshake1(ctx, req, handshaker, promise);
            return promise;
        } else {
            promise.addListener(new ChannelFutureListener() {
                @Override
                public void operationComplete(final ChannelFuture future) throws Exception {
                    if (future.isSuccess()) {
                        ctx.pipeline().addBefore(ctx.name(), null, new Utf8FrameValidator());
                        ctx.pipeline().addBefore(ctx.name(), null, new WebSocketCtrlFrameHandler());
                    }
                }
            });
            handshake0(ctx, req, handshaker, promise);
            return promise;
        }
    }

    protected void handshake1(final ChannelHandlerContext ctx, final FullHttpRequest req, final WebSocketServerHandshaker handshaker, final ChannelPromise promise) throws Exception {
        ctx.channel().config().setAutoRead(false);
        final HttpHeaders headers = req.headers();
        final String hostname = headers.getAsString("X-TARGET-ADDRESS");
        final int port = headers.getInt("X-TARGET-PORT", 0);

        /*-
         * PROTOCOL: through / connect
         */
        Channels.open(hostname, port, false, proxyGroup, new ChannelInboundHandlerAdapter() {
            @Override
            public void channelRegistered(final ChannelHandlerContext targetCtx) throws Exception {
                ctx.pipeline().addBefore(ctx.name(), "Socket->Socket", new SocketInboundRedirectHandler(targetCtx));
                targetCtx.pipeline().replace(targetCtx.name(), "Socket->Socket", new SocketInboundRedirectHandler(ctx));

                ctx.channel().config().setAutoRead(true);
                targetCtx.channel().config().setAutoRead(true);
            }

        }).addListener(f -> {
            if (f.isSuccess()) {
                log.warn("连接到目标地址({}/{}:{})", hostname, port, f.cause());
                handshaker.handshake(ctx.channel(), req, null, promise).addListener(new ChannelFutureListener() {
                    @Override
                    public void operationComplete(final ChannelFuture future) throws Exception {
                        if (future.isSuccess()) {
                            ctx.pipeline().remove("wsencoder");
                            ctx.pipeline().remove("wsdecoder");
                        }
                    }
                });
                // FIXME 握手失败关闭连接.
            } else {
                log.warn("连接到目标地址({}/{}:{})失败: {}", hostname, port, f.cause());
            }
        }).channel().closeFuture().addListener(f -> {
            if (ctx.channel().isActive()) {
                log.info("目标地址({}/{}:{})断开连接", hostname, port, f.cause());
                ctx.writeAndFlush(Unpooled.EMPTY_BUFFER).addListener(ChannelFutureListener.CLOSE);
            }
        });
    }

    protected void handshake0(final ChannelHandlerContext ctx, final FullHttpRequest req, final WebSocketServerHandshaker handshaker, final ChannelPromise promise) throws Exception {
        ctx.channel().config().setAutoRead(false);
        final HttpHeaders headers = req.headers();
        final String hostname = headers.getAsString("X-TARGET-ADDRESS");
        final int port = headers.getInt("X-TARGET-PORT", 0);

        /*-
         * PROTOCOL: through / connect
         */
        Channels.open(hostname, port, false, proxyGroup, new ChannelInboundHandlerAdapter() {
            @Override
            public void channelRegistered(final ChannelHandlerContext targetCtx) throws Exception {
                ctx.pipeline().addBefore(ctx.name(), "WebSocket->Socket", new SocketOverWebSocketDecodeHandler(targetCtx));
                targetCtx.pipeline().replace(targetCtx.name(), "Socket->WebSocket", new SocketOverWebSocketEncodeHandler(ctx));

                ctx.channel().config().setAutoRead(true);
                targetCtx.channel().config().setAutoRead(true);
            }

        }).addListener(f -> {
            if (f.isSuccess()) {
                log.warn("连接到目标地址({}/{}:{})", hostname, port, f.cause());
                handshaker.handshake(ctx.channel(), req, null, promise).addListener(new ChannelFutureListener() {
                    @Override
                    public void operationComplete(final ChannelFuture future) throws Exception {
                        Channel ch = future.channel();
                        System.out.println(ch);
                    }
                });
                // FIXME 握手失败关闭连接.
            } else {
                log.warn("连接到目标地址({}/{}:{})失败: {}", hostname, port, f.cause());
            }
        }).channel().closeFuture().addListener(f -> {
            if (ctx.channel().isActive()) {
                log.info("目标地址({}/{}:{})断开连接", hostname, port, f.cause());
                ctx.writeAndFlush(Unpooled.EMPTY_BUFFER).addListener(ChannelFutureListener.CLOSE);
            }
        });
    }

    private static void sendHttpResponse(ChannelHandlerContext ctx, HttpRequest req, HttpResponse res) {
        ChannelFuture f = ctx.channel().writeAndFlush(res);
        if (!isKeepAlive(req) || res.status().code() != 200) {
            f.addListener(ChannelFutureListener.CLOSE);
        }
    }

    private static String getWebSocketLocation(ChannelPipeline cp, HttpRequest req, String path) {
        String protocol = "ws";
        if (cp.get(SslHandler.class) != null) {
            // SSL in use so use Secure WebSockets
            protocol = "wss";
        }
        String host = req.headers().get(HttpHeaderNames.HOST);
        return protocol + "://" + host + path;
    }

    static WebSocketServerHandshaker getHandshaker(Channel channel) {
        return channel.attr(HANDSHAKER_ATTR_KEY).get();
    }

    static void setHandshaker(Channel channel, WebSocketServerHandshaker handshaker) {
        channel.attr(HANDSHAKER_ATTR_KEY).set(handshaker);
    }
}

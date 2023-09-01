package com.github.pangolin.proxy.backhaul.server;

import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.ChannelPromise;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.websocketx.CloseWebSocketFrame;
import io.netty.handler.codec.http.websocketx.PingWebSocketFrame;
import io.netty.handler.codec.http.websocketx.PongWebSocketFrame;
import io.netty.handler.codec.http.websocketx.Utf8FrameValidator;
import io.netty.handler.codec.http.websocketx.WebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketHandshakeException;
import io.netty.handler.codec.http.websocketx.WebSocketServerHandshaker;
import io.netty.handler.codec.http.websocketx.WebSocketServerHandshakerFactory;
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolHandler;
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolHandler.HandshakeComplete;
import io.netty.handler.ssl.SslHandler;
import io.netty.util.AttributeKey;
import io.netty.util.ReferenceCountUtil;
import lombok.extern.slf4j.Slf4j;

import java.lang.reflect.Constructor;
import java.util.List;

import static io.netty.handler.codec.http.HttpMethod.GET;
import static io.netty.handler.codec.http.HttpResponseStatus.FORBIDDEN;
import static io.netty.handler.codec.http.HttpUtil.isKeepAlive;
import static io.netty.handler.codec.http.HttpVersion.HTTP_1_1;

/**
 * @see io.netty.handler.codec.http.websocketx.WebSocketServerProtocolHandler
 * @see io.netty.handler.codec.http.websocketx.WebSocketServerProtocolHandshakeHandler
 */
@Slf4j
public abstract class WebSocketServerHandshakeNegotiationHandler extends ChannelInboundHandlerAdapter {
    protected static final AttributeKey<WebSocketServerHandshaker> HANDSHAKER_ATTR_KEY = AttributeKey.valueOf(WebSocketServerHandshaker.class, "HANDSHAKER");

    protected final String webSocketPath;
    protected final String subprotocols;
    protected final boolean allowExtensions;
    protected final int maxFramePayloadSize;
    protected final boolean allowMaskMismatch;
    protected final boolean checkStartsWith;

    public WebSocketServerHandshakeNegotiationHandler(final String webSocketPath, final String subprotocols,
                                                      final boolean allowExtensions, final int maxFrameSize, final boolean allowMaskMismatch) {
        this(webSocketPath, subprotocols, allowExtensions, maxFrameSize, allowMaskMismatch, false);
    }

    public WebSocketServerHandshakeNegotiationHandler(final String webSocketPath, final String subprotocols,
                                                      final boolean allowExtensions, final int maxFrameSize,
                                                      final boolean allowMaskMismatch, final boolean checkStartsWith) {
        this.webSocketPath = webSocketPath;
        this.subprotocols = subprotocols;
        this.allowExtensions = allowExtensions;
        this.maxFramePayloadSize = maxFrameSize;
        this.allowMaskMismatch = allowMaskMismatch;
        this.checkStartsWith = checkStartsWith;
    }

    /**
     * @see io.netty.handler.codec.http.websocketx.WebSocketServerProtocolHandler#handlerAdded(ChannelHandlerContext)
     * @see io.netty.handler.codec.http.websocketx.WebSocketServerProtocolHandler#decode(ChannelHandlerContext, WebSocketFrame, List)
     */
    @Override
    public void handlerAdded(final ChannelHandlerContext ctx) throws Exception {
        /*-
         * http server codec
         * -- wsencoder, wsdecoder
         * WebSocketServerProtocolHandshakeHandler --> 403 forbidden
         * Utf8FrameValidator
         * WebSocketServerProtocolHandler
         *   |- handle close frame
         *   |- exception caught
         * UdfHandler.
         */
        ctx.pipeline().addAfter(ctx.name(), null, new Utf8FrameValidator());
        ctx.pipeline().addAfter(ctx.name(), null, new WebSocketCtrlFrameHandler());
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        if (cause instanceof WebSocketHandshakeException) {
            final FullHttpResponse response = new DefaultFullHttpResponse(HTTP_1_1, HttpResponseStatus.BAD_REQUEST, Unpooled.wrappedBuffer(cause.getMessage().getBytes()));
            ctx.channel().writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
        } else {
            ctx.fireExceptionCaught(cause);
            ctx.close();
        }
    }

    /**
     * @see io.netty.handler.codec.http.websocketx.WebSocketServerProtocolHandshakeHandler
     */
    @Override
    public void channelRead(final ChannelHandlerContext ctx, Object msg) throws Exception {
        if (!(msg instanceof FullHttpRequest)) {
            log.warn("Connection closed by UNKNOWN message: {}", msg.getClass().getName());
            ReferenceCountUtil.release(msg);
            ctx.close();
            return;
        }

        final FullHttpRequest httpRequest = (FullHttpRequest) msg;
        if (isNotWebSocketPath(httpRequest)) {
            ctx.fireChannelRead(msg);
            return;
        }

        try {
            if (!GET.equals(httpRequest.method())) {
                sendHttpResponse(ctx, httpRequest, new DefaultFullHttpResponse(HTTP_1_1, FORBIDDEN));
                return;
            }

            final WebSocketServerHandshaker handshaker = newHandshaker(ctx, httpRequest);
            if (null == handshaker) {
                WebSocketServerHandshakerFactory.sendUnsupportedVersionResponse(ctx.channel());
                return;
            }

            final ChannelFuture handshakeFuture = handshake(ctx, httpRequest, handshaker, ctx.newPromise());
            handshakeFuture.addListener(new ChannelFutureListener() {
                @Override
                public void operationComplete(final ChannelFuture future) throws Exception {
                    if (!future.isSuccess()) {
                        ctx.fireExceptionCaught(future.cause());
                    } else {
                        // Kept for compatibility
                        ctx.fireUserEventTriggered(WebSocketServerProtocolHandler.ServerHandshakeStateEvent.HANDSHAKE_COMPLETE);
                        ctx.fireUserEventTriggered(newHandshakeComplete(httpRequest.uri(), httpRequest.headers(), handshaker.selectedSubprotocol()));
                    }
                }
            });
            ctx.channel().attr(HANDSHAKER_ATTR_KEY).set(handshaker);
            ctx.pipeline().replace(this, "WS403Responder", new HttpRequestForbiddenResponder());
        } finally {
            ReferenceCountUtil.release(msg);
        }
    }

    protected WebSocketServerHandshaker newHandshaker(final ChannelHandlerContext ctx, final FullHttpRequest httpRequest) {
        final WebSocketServerHandshakerFactory factory = new WebSocketServerHandshakerFactory(
                getWebSocketLocation(ctx.pipeline(), httpRequest, webSocketPath),
                subprotocols, allowExtensions, maxFramePayloadSize, allowMaskMismatch
        );
        return factory.newHandshaker(httpRequest);
    }

    protected abstract ChannelFuture handshake(final ChannelHandlerContext ctx, final FullHttpRequest httpRequest, final WebSocketServerHandshaker handshaker, final ChannelPromise promise) throws Exception;

    private boolean isNotWebSocketPath(FullHttpRequest req) {
        return checkStartsWith ? !req.uri().startsWith(webSocketPath) : !req.uri().equals(webSocketPath);
    }

    private static void sendHttpResponse(final ChannelHandlerContext ctx, final HttpRequest req, final HttpResponse res) {
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

    private static HandshakeComplete newHandshakeComplete(final String requestUri, final HttpHeaders httpHeaders, final String selectedSubprotocol) {
        try {
            final Constructor<HandshakeComplete> ctor = HandshakeComplete.class.getDeclaredConstructor(String.class, HttpHeaders.class, String.class);
            if (!ctor.isAccessible()) {
                ctor.setAccessible(true);
            }
            return ctor.newInstance(requestUri, httpHeaders, selectedSubprotocol);
        } catch (final Exception e) {
            throw new IllegalStateException(e);
        }
    }

    protected static class HttpRequestForbiddenResponder extends ChannelInboundHandlerAdapter {
        @Override
        public void channelRead(final ChannelHandlerContext ctx, final Object msg) throws Exception {
            if (msg instanceof FullHttpRequest) {
                ReferenceCountUtil.release(msg);
                ctx.channel().writeAndFlush(new DefaultFullHttpResponse(HTTP_1_1, FORBIDDEN));
            } else {
                ctx.fireChannelRead(msg);
            }
        }
    }

    /**
     * @see io.netty.handler.codec.http.websocketx.WebSocketServerProtocolHandler
     */
    public static class WebSocketCtrlFrameHandler extends SimpleChannelInboundHandler<WebSocketFrame> {

        @Override
        protected void channelRead0(final ChannelHandlerContext ctx, final WebSocketFrame frame) throws Exception {
            if (frame instanceof PingWebSocketFrame) {
                ctx.channel().writeAndFlush(new PongWebSocketFrame(frame.content().retain()));
            } else if (frame instanceof PongWebSocketFrame) {
                // Pong frames need to get ignored
            } else if (frame instanceof CloseWebSocketFrame) {
                final WebSocketServerHandshaker handshaker = ctx.channel().attr(HANDSHAKER_ATTR_KEY).get();
                if (null != handshaker) {
                    handshaker.close(ctx.channel(), ((CloseWebSocketFrame) frame).retain());
                } else {
                    ctx.channel().writeAndFlush(Unpooled.EMPTY_BUFFER).addListener(ChannelFutureListener.CLOSE);
                }
            } else {
                ctx.fireChannelRead(ReferenceCountUtil.retain(frame));
            }
        }

    }

}

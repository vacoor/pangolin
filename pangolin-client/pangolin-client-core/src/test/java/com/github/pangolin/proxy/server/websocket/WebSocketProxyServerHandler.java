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
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.QueryStringDecoder;
import io.netty.handler.codec.http.websocketx.CloseWebSocketFrame;
import io.netty.handler.codec.http.websocketx.Utf8FrameValidator;
import io.netty.handler.codec.http.websocketx.WebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketHandshakeException;
import io.netty.handler.codec.http.websocketx.WebSocketServerHandshaker;
import io.netty.handler.codec.http.websocketx.WebSocketServerHandshakerFactory;
import io.netty.handler.ssl.SslHandler;
import io.netty.util.AttributeKey;
import io.netty.util.ReferenceCountUtil;
import io.netty.util.internal.StringUtil;
import lombok.extern.slf4j.Slf4j;

import java.net.URI;
import java.util.List;

import static io.netty.handler.codec.http.HttpMethod.CONNECT;
import static io.netty.handler.codec.http.HttpMethod.GET;
import static io.netty.handler.codec.http.HttpResponseStatus.FORBIDDEN;
import static io.netty.handler.codec.http.HttpUtil.isKeepAlive;
import static io.netty.handler.codec.http.HttpVersion.HTTP_1_1;

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
         * http server codec
         * wsencoder, wsdecoder
         * WebSocketServerProtocolHandshakeHandler --> 403
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
    public void channelRead(final ChannelHandlerContext ctx, Object msg) throws Exception {
        try {
            if (!(msg instanceof FullHttpRequest) || !((FullHttpRequest) msg).decoderResult().isSuccess()) {
                log.warn("Connection closed by UNKNOWN message: {}", msg.getClass().getName());
                ctx.close();
                return;
            }

            final FullHttpRequest httpRequest = (FullHttpRequest) msg;
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
//                        ctx.fireUserEventTriggered(WebSocketServerProtocolHandler.ServerHandshakeStateEvent.HANDSHAKE_COMPLETE);
//                        ctx.fireUserEventTriggered(new WebSocketServerProtocolHandler.HandshakeComplete(httpRequest.uri(), httpRequest.headers(), handshaker.selectedSubprotocol()));
                    }
                }
            });
            ctx.channel().attr(HANDSHAKER_ATTR_KEY).set(handshaker);
//                        ctx.pipeline().replace(this, "WS403Responder", new HttpRequestForbiddenResponder());
        } finally {
            ReferenceCountUtil.release(msg);
        }
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

    private static void sendHttpResponse(ChannelHandlerContext ctx, HttpRequest req, HttpResponse res) {
        ChannelFuture f = ctx.channel().writeAndFlush(res);
        if (!isKeepAlive(req) || res.status().code() != 200) {
            f.addListener(ChannelFutureListener.CLOSE);
        }
    }

    protected WebSocketServerHandshaker newHandshaker(final ChannelHandlerContext ctx, final FullHttpRequest httpRequest) {
        final WebSocketServerHandshakerFactory factory = new WebSocketServerHandshakerFactory(
                getWebSocketLocation(ctx.pipeline(), httpRequest, ""),
                ",CONNECT", allowExtensions, maxFramePayloadSize, allowMaskMismatch
        );
        return factory.newHandshaker(httpRequest);
    }

    protected ChannelPromise handshake(final ChannelHandlerContext ctx, final FullHttpRequest httpRequest, final WebSocketServerHandshaker handshaker, final ChannelPromise promise) throws Exception {
        /*
         *         ws[s]://host:port/path   ws <--> ws
         *         tcp://host:port          ws <--> tcp
         *
         * CONNECT ws[s]://host:port/path   tcp <--> ws
         * CONNECT tcp://host:port          tcp <--> tcp
         */

        final String protocol = httpRequest.headers().get(HttpHeaderNames.SEC_WEBSOCKET_PROTOCOL);
        if (CONNECT.name().equalsIgnoreCase(protocol)) {
            tcpTunnelHandshake(ctx, httpRequest, handshaker, promise);
            return promise;
        } else {
            wsTunnelHandshake(ctx, httpRequest, handshaker, promise);
            return promise;
        }
    }

    private URI getTargetUri(final FullHttpRequest httpRequest) {
        final List<String> target = new QueryStringDecoder(httpRequest.uri()).parameters().get("target");
        return null != target && target.size() > 0 ? URI.create(target.get(target.size() - 1)) : null;
    }

    protected void tcpTunnelHandshake(final ChannelHandlerContext ctx, final FullHttpRequest req, final WebSocketServerHandshaker handshaker, final ChannelPromise promise) throws Exception {
        final URI targetUri = getTargetUri(req);
        final String hostname = targetUri.getHost();
        final int port = targetUri.getPort();

        /*-
         * PROTOCOL: through / connect
         */
        ctx.channel().config().setAutoRead(false);
        Channels.open(hostname, port, false, proxyGroup, new ChannelInboundHandlerAdapter() {
            @Override
            public void channelRegistered(final ChannelHandlerContext targetCtx) throws Exception {
                ctx.pipeline().addLast("Socket->Socket", new SocketInboundRedirectHandler(targetCtx));
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
                            ctx.pipeline().remove(WebSocketCtrlFrameHandler.class);
                            ctx.pipeline().remove(Utf8FrameValidator.class);
                            ctx.pipeline().remove("wsencoder");
                            ctx.pipeline().remove("wsdecoder");
                            ctx.pipeline().remove(ctx.name());
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
                ctx.channel().writeAndFlush(Unpooled.EMPTY_BUFFER).addListener(ChannelFutureListener.CLOSE);
            }
        });
    }

    protected void wsTunnelHandshake(final ChannelHandlerContext ctx, final FullHttpRequest req, final WebSocketServerHandshaker handshaker, final ChannelPromise promise) throws Exception {
        final URI targetUri = getTargetUri(req);
        final String hostname = targetUri.getHost();
        final int port = targetUri.getPort();

        ctx.channel().config().setAutoRead(false);

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
                ctx.channel().writeAndFlush(Unpooled.EMPTY_BUFFER).addListener(ChannelFutureListener.CLOSE);
            }
        });
    }


    private FullHttpResponse newForbiddenResponse(final HttpRequest httpRequest) {
        return new DefaultFullHttpResponse(httpRequest.protocolVersion(), FORBIDDEN);
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

    private class WebSocketCtrlFrameHandler extends SimpleChannelInboundHandler<WebSocketFrame> {

        @Override
        protected void channelRead0(final ChannelHandlerContext ctx, final WebSocketFrame frame) throws Exception {
            /*
            if (frame instanceof PingWebSocketFrame) {
            } else if (frame instanceof PongWebSocketFrame) {
            } else */ if (frame instanceof CloseWebSocketFrame) {
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
}

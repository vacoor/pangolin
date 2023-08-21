package com.github.pangolin.proxy.server;

import com.github.pangolin.util.Channels;
import com.github.pangolin.util.Redirects;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.handler.codec.MessageToMessageDecoder;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
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
import io.netty.handler.ssl.SslHandler;
import io.netty.util.AttributeKey;

import java.util.List;

import static io.netty.handler.codec.http.HttpMethod.GET;
import static io.netty.handler.codec.http.HttpResponseStatus.FORBIDDEN;
import static io.netty.handler.codec.http.HttpUtil.isKeepAlive;
import static io.netty.handler.codec.http.HttpVersion.HTTP_1_1;

/**
 *
 */
public class WebSocketProxyServerHandler extends ChannelInboundHandlerAdapter {
    private static final AttributeKey<WebSocketServerHandshaker> HANDSHAKER_ATTR_KEY = AttributeKey.valueOf(WebSocketServerHandshaker.class, "HANDSHAKER");

    private final String websocketPath;
    private final String subprotocols;
    private final boolean allowExtensions;
    private final int maxFramePayloadSize;
    private final boolean allowMaskMismatch;
    private final boolean checkStartsWith;

    WebSocketProxyServerHandler(String websocketPath, String subprotocols, boolean allowExtensions, int maxFrameSize, boolean allowMaskMismatch) {
        this(websocketPath, subprotocols, allowExtensions, maxFrameSize, allowMaskMismatch, false);
    }

    public WebSocketProxyServerHandler(String websocketPath, String subprotocols, boolean allowExtensions, int maxFrameSize, boolean allowMaskMismatch, boolean checkStartsWith) {
        this.websocketPath = websocketPath;
        this.subprotocols = subprotocols;
        this.allowExtensions = allowExtensions;
        maxFramePayloadSize = maxFrameSize;
        this.allowMaskMismatch = allowMaskMismatch;
        this.checkStartsWith = checkStartsWith;
    }

    @Override
    public void handlerAdded(final ChannelHandlerContext ctx) throws Exception {
        /*-
         * handshake -> 403
         * utf8
         * protocol
         */
        final ChannelPipeline cp = ctx.pipeline();
        ctx.pipeline().addAfter(ctx.name(), null, new MessageToMessageDecoder<WebSocketFrame>() {

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
    }

    @Override
    public void channelRead(final ChannelHandlerContext ctx, Object msg) throws Exception {
        final FullHttpRequest req = (FullHttpRequest) msg;
        if (isNotWebSocketPath(req)) {
            ctx.fireChannelRead(msg);
            return;
        }

        try {
            if (req.method() != GET) {
                sendHttpResponse(ctx, req, new DefaultFullHttpResponse(HTTP_1_1, FORBIDDEN));
                return;
            }

            final WebSocketServerHandshakerFactory wsFactory = new WebSocketServerHandshakerFactory(
                    getWebSocketLocation(ctx.pipeline(), req, websocketPath),
                    subprotocols, allowExtensions, maxFramePayloadSize, allowMaskMismatch
            );
            final WebSocketServerHandshaker handshaker = wsFactory.newHandshaker(req);
            if (handshaker == null) {
                WebSocketServerHandshakerFactory.sendUnsupportedVersionResponse(ctx.channel());
            } else {
                //
                ctx.channel().config().setAutoRead(false);
                final String hostname = req.headers().getAsString("X-TARGET-ADDRESS");
                final int port = req.headers().getInt("X-TARGET-PORT", 0);
                Channels.open(hostname, port, false, new NioEventLoopGroup(), new ChannelInboundHandlerAdapter() {
                    @Override
                    public void channelRegistered(final ChannelHandlerContext targetCtx) throws Exception {
                        ctx.pipeline().replace(ctx.handler(), null, Redirects.webSocketRedirectToSocket(targetCtx));
                        targetCtx.pipeline().replace(targetCtx.handler(), null, Redirects.socketRedirectToWebSocket(targetCtx));

                        ctx.channel().config().setAutoRead(true);
                        targetCtx.channel().config().setAutoRead(true);
                    }
                }).addListener(f -> {
                    if (f.isSuccess()) {
//                    log.debug("连接到目标地址({}/{}:{})成功", addrType, addr, port);
//                    requestCtx.writeAndFlush(new DefaultSocks5CommandResponse(Socks5CommandStatus.SUCCESS, addrType));

                        final ChannelFuture handshakeFuture = handshaker.handshake(ctx.channel(), req);
                        handshakeFuture.addListener(new ChannelFutureListener() {
                            @Override
                            public void operationComplete(ChannelFuture future) throws Exception {
                                if (!future.isSuccess()) {
                                    ctx.fireExceptionCaught(future.cause());
                                } else {
                                    // Kept for compatibility
//                            ctx.fireUserEventTriggered(WebSocketServerProtocolHandler.ServerHandshakeStateEvent.HANDSHAKE_COMPLETE);
//                            ctx.fireUserEventTriggered(new WebSocketServerProtocolHandler.HandshakeComplete(req.uri(), req.headers(), handshaker.selectedSubprotocol()));
                                }
                            }
                        });
                        setHandshaker(ctx.channel(), handshaker);
                        /*
                        ctx.pipeline().replace(this, "WS403Responder", new ChannelInboundHandlerAdapter() {
                            @Override
                            public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
                                if (msg instanceof FullHttpRequest) {
                                    ((FullHttpRequest) msg).release();
                                    FullHttpResponse response =
                                            new DefaultFullHttpResponse(HTTP_1_1, HttpResponseStatus.FORBIDDEN);
                                    ctx.channel().writeAndFlush(response);
                                } else {
                                    ctx.fireChannelRead(msg);
                                }
                            }
                        });
                        */
                    } else {
//                        log.warn("连接到目标地址({}/{}:{})失败: {}", addrType, addr, port, f.cause());
//                        requestCtx.writeAndFlush(new DefaultSocks5CommandResponse(Socks5CommandStatus.FAILURE, addrType));
                    }
                }).sync().channel().closeFuture().addListener(f -> {
                    /*
                    if (requestCtx.channel().isActive()) {
                        log.info("目标地址({}/{}:{})断开连接", addrType, addr, port, f.cause());
                        requestCtx.writeAndFlush(Unpooled.EMPTY_BUFFER).addListener(ChannelFutureListener.CLOSE);
                    }
                    */
                });

            }
        } finally {
            req.release();
        }
    }

    private boolean isNotWebSocketPath(FullHttpRequest req) {
        return checkStartsWith ? !req.uri().startsWith(websocketPath) : !req.uri().equals(websocketPath);
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

package com.github.pangolin.routing.handler.internal.server;

import com.github.pangolin.handler.TcpInboundRedirectHandler;
import com.github.pangolin.handler.TcpOverWebSocketDecodeHandler;
import com.github.pangolin.handler.TcpOverWebSocketEncodeHandler;
import com.github.pangolin.handler.WebSocketServerHandshakeNegotiationHandler;
import com.github.pangolin.routing.handler.internal.server.support.SocketChannelFactory;
import com.github.pangolin.routing.handler.internal.server.support.StandardSocketChannelFactory;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelConfig;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.QueryStringDecoder;
import io.netty.handler.codec.http.websocketx.Utf8FrameValidator;
import io.netty.handler.codec.http.websocketx.WebSocketServerHandshaker;
import lombok.extern.slf4j.Slf4j;

import java.net.InetSocketAddress;
import java.net.URI;
import java.util.List;

import static io.netty.handler.codec.http.HttpMethod.CONNECT;

/**
 * @see io.netty.handler.codec.http.websocketx.WebSocketServerProtocolHandler
 */
@Slf4j
public class WebSocketProxyServerHandler extends WebSocketServerHandshakeNegotiationHandler {
    private static final String WEB_SOCKET_PATH = "";
    private static final String PROTOCOLS = "CONNECT,";

    private final SocketChannelFactory factory;

    public WebSocketProxyServerHandler(boolean allowExtensions, int maxFrameSize, boolean allowMaskMismatch) {
        this(allowExtensions, maxFrameSize, allowMaskMismatch, new StandardSocketChannelFactory());
    }

    public WebSocketProxyServerHandler(boolean allowExtensions, int maxFrameSize, boolean allowMaskMismatch, final SocketChannelFactory factory) {
        super(WEB_SOCKET_PATH, PROTOCOLS, allowExtensions, maxFrameSize, allowMaskMismatch, true);
        this.factory = factory;
    }

    @Override
    protected ChannelFuture handshake(final ChannelHandlerContext ctx, final FullHttpRequest httpRequest, final WebSocketServerHandshaker handshaker, final ChannelPromise promise) throws Exception {
        /*
         *         ws[s]://host:port/path   ws <--> ws
         *         tcp://host:port          ws <--> tcp
         *
         * CONNECT ws[s]://host:port/path   tcp <--> ws
         * CONNECT tcp://host:port          tcp <--> tcp
         */

        final String protocol = httpRequest.headers().get(HttpHeaderNames.SEC_WEBSOCKET_PROTOCOL);
        if (CONNECT.name().equalsIgnoreCase(protocol)) {
            return tcpTunnelHandshake(ctx, httpRequest, handshaker, promise);
        } else {
            return wsTunnelHandshake(ctx, httpRequest, handshaker, promise);
        }
    }

    private URI getTargetUri(final FullHttpRequest httpRequest) {
        final List<String> target = new QueryStringDecoder(httpRequest.uri()).parameters().get("target");
        return null != target && target.size() > 0 ? URI.create(target.get(target.size() - 1)) : null;
    }

    protected ChannelFuture tcpTunnelHandshake(final ChannelHandlerContext ctx, final FullHttpRequest req, final WebSocketServerHandshaker handshaker, final ChannelPromise promise) throws Exception {
        final URI targetUri = getTargetUri(req);
        final String hostname = targetUri.getHost();
        final int port = targetUri.getPort();

        /*-
         * PROTOCOL: through / connect
         */
        ctx.channel().config().setAutoRead(false);
        ChannelConfig c = ctx.channel().config();
        // FIXME
        final InetSocketAddress addr = InetSocketAddress.createUnresolved(hostname, port);
        factory.open(addr, c.getConnectTimeoutMillis(), false, ctx.channel().eventLoop(), new ChannelInboundHandlerAdapter() {
            @Override
            public void channelRegistered(final ChannelHandlerContext targetCtx) throws Exception {
                ctx.pipeline().addLast("Socket->Socket", new TcpInboundRedirectHandler(targetCtx));
                targetCtx.pipeline().replace(targetCtx.name(), "Socket->Socket", new TcpInboundRedirectHandler(ctx));

                ctx.channel().config().setAutoRead(true);
                targetCtx.channel().config().setAutoRead(true);
            }

        }).addListener(f -> {
            if (f.isSuccess()) {
                log.warn("连接到目标地址({}/{}:{})", hostname, port, f.cause());
                promise.addListener(new ChannelFutureListener() {
                    @Override
                    public void operationComplete(final ChannelFuture future) throws Exception {
                        if (future.isSuccess()) {
                            ctx.pipeline().remove(WebSocketCtrlFrameHandler.class);
                            ctx.pipeline().remove(Utf8FrameValidator.class);
                            ctx.pipeline().remove("wsencoder");
                            ctx.pipeline().remove("wsdecoder");
//                            ctx.pipeline().remove(ctx.name());
                        }
                    }
                });
                handshaker.handshake(ctx.channel(), req, null, promise);
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
        return promise;
    }

    protected ChannelFuture wsTunnelHandshake(final ChannelHandlerContext ctx, final FullHttpRequest req, final WebSocketServerHandshaker handshaker, final ChannelPromise promise) throws Exception {
        final URI targetUri = getTargetUri(req);
        final String hostname = targetUri.getHost();
        final int port = targetUri.getPort();

        ctx.channel().config().setAutoRead(false);

        /*-
         * PROTOCOL: through / connect
         */

        ChannelConfig c = ctx.channel().config();
        // FIXME
        final InetSocketAddress addr = InetSocketAddress.createUnresolved(hostname, port);
        factory.open(addr, c.getConnectTimeoutMillis(), false, ctx.channel().eventLoop(), new ChannelInboundHandlerAdapter() {
            @Override
            public void channelRegistered(final ChannelHandlerContext targetCtx) throws Exception {
                ctx.pipeline().addLast("WebSocket->Socket", new TcpOverWebSocketDecodeHandler(targetCtx));
                targetCtx.pipeline().replace(targetCtx.name(), "Socket->WebSocket", new TcpOverWebSocketEncodeHandler(ctx));

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
        return promise;
    }

}

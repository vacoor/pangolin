package com.github.pangolin.routing.support.handler.server;

import com.github.pangolin.handler.TcpInboundRedirectHandler;
import com.github.pangolin.handler.TcpOverWebSocketDecodeHandler;
import com.github.pangolin.handler.TcpOverWebSocketEncodeHandler;
import com.github.pangolin.handler.WebSocketServerHandshakeNegotiationHandler;
import com.github.pangolin.routing.support.SocketChannelFactory;
import com.github.pangolin.routing.support.StandardSocketChannelFactory;
import com.github.pangolin.util.Util;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.QueryStringDecoder;
import io.netty.handler.codec.http.websocketx.Utf8FrameValidator;
import io.netty.handler.codec.http.websocketx.WebSocketServerHandshaker;
import lombok.extern.slf4j.Slf4j;

import java.net.InetSocketAddress;
import java.util.List;
import java.util.Map;

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
        this(allowExtensions, maxFrameSize, allowMaskMismatch, new StandardSocketChannelFactory(null));
    }

    public WebSocketProxyServerHandler(boolean allowExtensions, int maxFrameSize, boolean allowMaskMismatch, final SocketChannelFactory factory) {
        super(WEB_SOCKET_PATH, PROTOCOLS, allowExtensions, maxFrameSize, allowMaskMismatch, true);
        this.factory = factory;
    }

    @Override
    protected ChannelFuture handshake(final ChannelHandlerContext handshakeCtx,
                                      final FullHttpRequest handshakeRequest,
                                      final WebSocketServerHandshaker handshaker,
                                      final ChannelPromise promise) throws Exception {
        final String protocol = handshakeRequest.headers().get(HttpHeaderNames.SEC_WEBSOCKET_PROTOCOL);
        final boolean downgrade = CONNECT.name().equalsIgnoreCase(protocol);
        return handshake0(handshakeCtx, handshakeRequest, handshaker, downgrade, promise);
    }


    protected ChannelFuture handshake0(final ChannelHandlerContext handshakeCtx,
                                       final FullHttpRequest handshakeRequest,
                                       final WebSocketServerHandshaker handshaker,
                                       final boolean downgrade,
                                       final ChannelPromise handshakePromise) throws Exception {
        final Map<String, List<String>> params = new QueryStringDecoder(handshakeRequest.uri()).parameters();
        final InetSocketAddress target = parseTarget(Util.last(params, "target"));

        handshakeCtx.channel().config().setAutoRead(false);
        final ChannelConfig c = handshakeCtx.channel().config();

        factory.open(
                target, c.getConnectTimeoutMillis(), false, handshakeCtx.channel().eventLoop(), new ChannelInboundHandlerAdapter()
        ).addListener(new ChannelFutureListener() {
            @Override
            public void operationComplete(final ChannelFuture future) throws Exception {
                if (!future.isSuccess()) {
                    // connection to proxy failed
                    // XXX close handshake connection.
                    return;
                }

                handshaker.handshake(
                        handshakeCtx.channel(), handshakeRequest, null, handshakePromise
                ).addListener(new ChannelFutureListener() {
                    @Override
                    public void operationComplete(final ChannelFuture handshakeFuture) throws Exception {
                        if (!handshakeFuture.isSuccess()) {
                            // handshake failed.
                            // XXX close proxy connection
                            // XXX close handshake connection.
                            return;
                        }

                        final ChannelHandlerContext targetCtx = future.channel().pipeline().lastContext();
                        if (!downgrade) {
                            targetCtx.pipeline().addLast("Socket->WebSocket", new TcpOverWebSocketEncodeHandler(handshakeCtx));
                            handshakeCtx.pipeline().addLast("WebSocket->Socket", new TcpOverWebSocketDecodeHandler(targetCtx));
                        } else {
                            targetCtx.pipeline().addLast("Socket->Socket", new TcpInboundRedirectHandler(handshakeCtx));
                            handshakeCtx.pipeline().addLast("Socket->Socket", new TcpInboundRedirectHandler(targetCtx));

                            handshakeCtx.pipeline().remove("wsencoder");
                            handshakeCtx.pipeline().remove("wsdecoder");
                            handshakeCtx.pipeline().remove("WS403Responder");
                            handshakeCtx.pipeline().remove(Utf8FrameValidator.class);
                            handshakeCtx.pipeline().remove(WebSocketCtrlFrameHandler.class);
                        }

                        targetCtx.channel().config().setAutoRead(true);
                        handshakeCtx.channel().config().setAutoRead(true);
                    }
                });
            }
        }).channel().closeFuture().addListener(f -> {
            if (handshakeCtx.channel().isActive()) {
                log.info("目标地址{}断开连接", target, f.cause());
                handshakeCtx.channel().writeAndFlush(Unpooled.EMPTY_BUFFER).addListener(ChannelFutureListener.CLOSE);
            }
        });
        return handshakePromise;
    }

    private InetSocketAddress parseTarget(final String target) {
        if (null == target || (target.contains("://") && !target.startsWith("tcp://"))) {
            return null;
        }
        final String hostAndPort = target.startsWith("tcp://") ? target.substring(6) : target;
        // resolve ?
        final String[] split = hostAndPort.split(":", 2);
        return InetSocketAddress.createUnresolved(split[0], Integer.parseInt(split[1]));
    }
}

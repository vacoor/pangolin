package com.github.pangolin.server;

import com.github.pangolin.handler.TcpOverWebSocketDecodeHandler;
import com.github.pangolin.handler.TcpOverWebSocketEncodeHandler;
import com.github.pangolin.handler.WebSocketInboundRedirectHandler;
import com.github.pangolin.server.mgt.WebSocketBackhaulTunnelServerConsoleHandler;
import com.github.pangolin.util.Util;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.http.QueryStringDecoder;
import io.netty.handler.codec.http.websocketx.BinaryWebSocketFrame;
import io.netty.handler.codec.http.websocketx.CloseWebSocketFrame;
import io.netty.handler.codec.http.websocketx.Utf8FrameValidator;
import io.netty.handler.codec.http.websocketx.WebSocketCloseStatus;
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolHandler;
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolHandler.HandshakeComplete;
import io.netty.util.ReferenceCountUtil;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.FutureListener;
import lombok.extern.slf4j.Slf4j;

import java.net.InetSocketAddress;
import java.util.List;
import java.util.Map;

/**
 *
 */
@Slf4j
public class WebSocketBackhaulTunnelServerHandler extends ChannelInboundHandlerAdapter {
    /**
     * @see #PROTO_AGENT_SERVICE
     * @deprecated
     */
    @Deprecated
    private static final String PROTO_AGENT_REGISTER = "PASSIVE-REG";

    /**
     * @see #PROTO_CONN_BACKHAUL
     * @deprecated
     */
    @Deprecated
    private static final String PROTO_CONN_BACKHAUL_LEGACY = "PASSIVE";

    /**
     * Agent register.
     */
    private static final String PROTO_AGENT_SERVICE = "SERVICE";

    /**
     * Client TCP over WebSocket connect.
     */
    private static final String PROTO_WS_CONNECT = "";

    /**
     * Client WebSocket degrade to TCP socket connect.
     */
    private static final String PROTO_TCP_CONNECT = "CONNECT";

    /**
     * Agent backhaul connection.
     */
    private static final String PROTO_CONN_BACKHAUL = "BACKHAUL";

    /**
     * WebSocket management console.
     */
    private static final String PROTO_MGR_CONSOLE = "CONSOLE";

    private static final String PARAM_AGENT = "agent";
    private static final String PARAM_TARGET = "target";
    private static final String PARAM_BACKHAUL_ID = "id";

    private final WebSocketBackhaulTunnelServerEngine webSocketBackhaulTunnelServerEngine;
    private final WebSocketBackhaulTunnelServerForwarder webSocketBackhaulTunnelServerForwarder;

    public WebSocketBackhaulTunnelServerHandler(final WebSocketBackhaulTunnelServerEngine webSocketBackhaulTunnelServerEngine,
                                                final WebSocketBackhaulTunnelServerForwarder webSocketBackhaulTunnelServerForwarder) {
        this.webSocketBackhaulTunnelServerEngine = webSocketBackhaulTunnelServerEngine;
        this.webSocketBackhaulTunnelServerForwarder = webSocketBackhaulTunnelServerForwarder;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void userEventTriggered(final ChannelHandlerContext ctx, final Object evt) throws Exception {
        if (evt instanceof HandshakeComplete) {
            final HandshakeComplete handshake = (HandshakeComplete) evt;
            final String rawProtocol = handshake.selectedSubprotocol();
            final String protocolToUse = null != rawProtocol ? rawProtocol : PROTO_WS_CONNECT;

            if (PROTO_AGENT_REGISTER.equals(protocolToUse) || PROTO_AGENT_SERVICE.equals(protocolToUse)) {
                // XXX authorize.
                if (!webSocketBackhaulTunnelServerEngine.agentRegistered(handshake, ctx)) {
                    ctx.writeAndFlush(new CloseWebSocketFrame(
                            WebSocketCloseStatus.INVALID_PAYLOAD_DATA
                    )).addListener(ChannelFutureListener.CLOSE);
                } else {
                    ctx.pipeline().replace(ctx.name(), null, new ChannelInboundHandlerAdapter() {
                        /**
                         * {@inheritDoc}
                         */
                        @Override
                        public void channelRead(final ChannelHandlerContext ctx, final Object msg) throws Exception {
                            if (msg instanceof BinaryWebSocketFrame) {
                                try {
                                    webSocketBackhaulTunnelServerEngine.agentResponded((BinaryWebSocketFrame) msg, ctx);
                                } finally {
                                    ReferenceCountUtil.release(msg);
                                }
                            } else {
                                ctx.fireChannelRead(msg);
                            }
                        }
                    });
                }
            } else if (PROTO_WS_CONNECT.equals(protocolToUse)) {
                // XXX authorize.
                if (!authenticate(handshake, ctx)) {
                    ctx.writeAndFlush(new CloseWebSocketFrame(
                            WebSocketCloseStatus.INVALID_PAYLOAD_DATA
                    )).addListener(ChannelFutureListener.CLOSE);
                } else {
                    handshake(handshake, ctx, false);
                }
            } else if (PROTO_TCP_CONNECT.equals(protocolToUse)) {
                // XXX authorize.
                if (!authenticate(handshake, ctx)) {
                    ctx.writeAndFlush(new CloseWebSocketFrame(
                            WebSocketCloseStatus.INVALID_PAYLOAD_DATA
                    )).addListener(ChannelFutureListener.CLOSE);
                } else {
                    handshake(handshake, ctx, true);
                }
            } else if (PROTO_CONN_BACKHAUL_LEGACY.equals(protocolToUse) || PROTO_CONN_BACKHAUL.equals(protocolToUse)) {
                final Map<String, List<String>> params = new QueryStringDecoder(handshake.requestUri()).parameters();
                final String id = Util.last(params, PARAM_BACKHAUL_ID);
                if (null != id && !id.isEmpty()) {
                    webSocketBackhaulTunnelServerEngine.finishHandshake(id, ctx);
                } else {
                    ctx.writeAndFlush(new CloseWebSocketFrame(
                            WebSocketCloseStatus.INVALID_PAYLOAD_DATA
                    )).addListener(ChannelFutureListener.CLOSE);
                }
            } else if (PROTO_MGR_CONSOLE.equals(protocolToUse)) {
                // XXX authorize.
                ctx.pipeline().replace(ctx.name(), null, new WebSocketBackhaulTunnelServerConsoleHandler(
                        webSocketBackhaulTunnelServerEngine, webSocketBackhaulTunnelServerForwarder
                ));
            } else {
                ctx.writeAndFlush(new CloseWebSocketFrame(WebSocketCloseStatus.PROTOCOL_ERROR)).addListener(ChannelFutureListener.CLOSE);
            }
        }
        ctx.fireUserEventTriggered(evt);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void exceptionCaught(final ChannelHandlerContext ctx, final Throwable cause) throws Exception {
        log.error("Connection abort: {}", cause.getMessage(), cause);
        ctx.writeAndFlush(new CloseWebSocketFrame(
                WebSocketCloseStatus.INTERNAL_SERVER_ERROR, cause.getMessage()
        )).addListener(ChannelFutureListener.CLOSE);
    }

    private boolean authenticate(final HandshakeComplete handshake, final ChannelHandlerContext accessCtx) {
        final String accessTokenInSys = System.getProperty("websocket.bridge.access_token");
        if (null == accessTokenInSys || accessTokenInSys.isEmpty()) {
            return true;
        }
        final String authorization = handshake.requestHeaders().get("Authorization");
        if (null != authorization && authorization.startsWith("Bearer ")) {
            if (accessTokenInSys.equals(authorization.substring(7))) {
                return true;
            }
        }

        final Map<String, List<String>> params = new QueryStringDecoder(handshake.requestUri()).parameters();
        return accessTokenInSys.equals(Util.last(params, "access_token"));
    }

    /**
     * TCP over websocket or websocket degrade to TCP.
     *
     * @param degrade websocket degrade to TCP socket
     */
    private void handshake(final HandshakeComplete handshake,
                           final ChannelHandlerContext accessCtx, final boolean degrade) {
        final Map<String, List<String>> params = new QueryStringDecoder(handshake.requestUri()).parameters();
        final String agentKey = Util.last(params, PARAM_AGENT);
        final InetSocketAddress target = parseTarget(Util.last(params, PARAM_TARGET));

        if (null == agentKey || agentKey.isEmpty() || null == target) {
            accessCtx.writeAndFlush(new CloseWebSocketFrame(
                    WebSocketCloseStatus.INVALID_PAYLOAD_DATA
            )).addListener(ChannelFutureListener.CLOSE);
            return;
        }

        log.info("[{}] Establishing WebSocket connection to {} via {}", accessCtx.channel().id(), target, agentKey);

        accessCtx.channel().config().setAutoRead(false);
        webSocketBackhaulTunnelServerEngine.handshake(
                accessCtx, agentKey, target, accessCtx.executor().newPromise()
        ).addListener(new FutureListener<ChannelHandlerContext>() {
            @Override
            public void operationComplete(final Future<ChannelHandlerContext> backhaulFuture) throws Exception {
                if (backhaulFuture.isSuccess()) {
                    final ChannelHandlerContext backhaulCtx = backhaulFuture.getNow();
                    backhaulCtx.channel().config().setAutoRead(false);

                    log.info("[{}] WebSocket connection established to {} via {}", accessCtx.channel().id(), target, agentKey);

                    if (!degrade) {
                        /*-
                         * client <--ws--> server <--ws--> agent
                         */
                        accessCtx.pipeline().replace(accessCtx.name(), null, new WebSocketInboundRedirectHandler(backhaulCtx));
                        backhaulCtx.pipeline().replace(backhaulCtx.name(), null, new WebSocketInboundRedirectHandler(accessCtx));
                    } else {
                        /*-
                         * client <--ws--> server <--ws--> agent
                         * degrade to:
                         * client <--socket--> server <--ws--> agent
                         */
                        accessCtx.pipeline().replace(accessCtx.name(), null, new TcpOverWebSocketEncodeHandler(backhaulCtx));
                        backhaulCtx.pipeline().replace(backhaulCtx.name(), null, new TcpOverWebSocketDecodeHandler(accessCtx));

                        /*-
                         * remove websocket codec.
                         *
                         * @see io.netty.handler.codec.http.websocketx.WebSocketServerHandshaker#handshake
                         */
                        accessCtx.pipeline().remove("wsencoder");
                        accessCtx.pipeline().remove("wsdecoder");
                        accessCtx.pipeline().remove("WS403Responder");
                        accessCtx.pipeline().remove(Utf8FrameValidator.class);
                        accessCtx.pipeline().remove(WebSocketServerProtocolHandler.class);
                    }

                    accessCtx.channel().config().setAutoRead(true);
                    backhaulCtx.channel().config().setAutoRead(true);
                } else {
                    final Throwable cause = backhaulFuture.cause();

                    log.warn("[{}] WebSocket connection to {} via {} failed: {}", accessCtx.channel().id(), target, agentKey, cause.getMessage());

                    accessCtx.writeAndFlush(
                            new CloseWebSocketFrame(WebSocketCloseStatus.ENDPOINT_UNAVAILABLE, cause.getMessage())
                    ).addListener(ChannelFutureListener.CLOSE);
                }
            }
        });
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

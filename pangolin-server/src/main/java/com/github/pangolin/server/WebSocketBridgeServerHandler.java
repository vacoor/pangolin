package com.github.pangolin.server;

import com.github.pangolin.handler.TcpOverWebSocketDecodeHandler;
import com.github.pangolin.handler.TcpOverWebSocketEncodeHandler;
import com.github.pangolin.handler.WebSocketInboundRedirectHandler;
import com.github.pangolin.server.shell.WebSocketBridgeServerConsoleHandler;
import com.github.pangolin.util.Util;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.base64.Base64;
import io.netty.handler.codec.base64.Base64Dialect;
import io.netty.handler.codec.http.QueryStringDecoder;
import io.netty.handler.codec.http.websocketx.*;
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolHandler.HandshakeComplete;
import io.netty.util.CharsetUtil;
import io.netty.util.ReferenceCountUtil;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.FutureListener;
import lombok.extern.slf4j.Slf4j;

import java.net.*;

import static com.github.pangolin.server.WebSocketBridgeServerEngine.*;

/**
 *
 */
@Slf4j
public class WebSocketBridgeServerHandler extends ChannelInboundHandlerAdapter {

    /**
     * Agent register.
     */
    private static final String PROTO_AGENT_SERVICE = "SERVICE";

    /**
     * Agent backhaul connection.
     */
    private static final String PROTO_AGENT_BACKHAUL = "BACKHAUL";

    /**
     * TCP over WebSocket connection.
     */
    private static final String PROTO_WS_CONNECT = "";

    /**
     * WebSocket downgrade to TCP socket connection.
     */
    private static final String PROTO_TCP_CONNECT = "CONNECT";

    /**
     * WebSocket management console.
     */
    private static final String PROTO_MGR_CONSOLE = "CONSOLE";

    private final String endpointPath;

    private final WebSocketBridgeServerEngine webSocketBridgeServerEngine;
    private final WebSocketBridgeServerForwarder webSocketBridgeServerForwarder;

    public WebSocketBridgeServerHandler(final String endpointPath,
                                        final WebSocketBridgeServerEngine webSocketBridgeServerEngine,
                                        final WebSocketBridgeServerForwarder webSocketBridgeServerForwarder) {
        this.endpointPath = endpointPath;
        this.webSocketBridgeServerEngine = webSocketBridgeServerEngine;
        this.webSocketBridgeServerForwarder = webSocketBridgeServerForwarder;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void userEventTriggered(final ChannelHandlerContext ctx, final Object evt) throws Exception {
        if (evt instanceof HandshakeComplete) {
            final HandshakeComplete handshake = (HandshakeComplete) evt;
            final String rawSubprotocol = handshake.selectedSubprotocol();
            final String subprotocol = null != rawSubprotocol ? rawSubprotocol : PROTO_WS_CONNECT;

            if (PROTO_AGENT_SERVICE.equals(subprotocol)) {
                /*-
                 * SERVICE {endpoint}/{tunnelKey}.
                 */
                final String tunnelKey = getPathWithinEndpoint(handshake);
                if (null == tunnelKey || tunnelKey.isEmpty()) {
                    ctx.writeAndFlush(new CloseWebSocketFrame(WebSocketCloseStatus.ENDPOINT_UNAVAILABLE)).addListener(ChannelFutureListener.CLOSE);
                } else {
                    final ByteBuf handshakePayload = getHandshakePayload(handshake);
                    if (null == handshakePayload) {
                        ctx.writeAndFlush(new CloseWebSocketFrame(WebSocketCloseStatus.INVALID_PAYLOAD_DATA)).addListener(ChannelFutureListener.CLOSE);
                    } else {
                        try {
                            if (!registerAgent(tunnelKey, handshakePayload, ctx)) {
                                ctx.writeAndFlush(new CloseWebSocketFrame(WebSocketCloseStatus.INVALID_PAYLOAD_DATA)).addListener(ChannelFutureListener.CLOSE);
                            }
                        } finally {
                            handshakePayload.release();
                        }
                    }
                }
            } else if (PROTO_WS_CONNECT.equals(subprotocol) || PROTO_TCP_CONNECT.equals(subprotocol)) {
                /*-
                 * CONNECT {endpoint}/{tunnelKey}.
                 */
                final String tunnelKey = getPathWithinEndpoint(handshake);
                if (null == tunnelKey || tunnelKey.isEmpty()) {
                    ctx.writeAndFlush(new CloseWebSocketFrame(WebSocketCloseStatus.ENDPOINT_UNAVAILABLE)).addListener(ChannelFutureListener.CLOSE);
                } else {
                    final ByteBuf handshakePayload = getHandshakePayload(handshake);
                    if (null == handshakePayload) {
                        ctx.writeAndFlush(new CloseWebSocketFrame(WebSocketCloseStatus.INVALID_PAYLOAD_DATA)).addListener(ChannelFutureListener.CLOSE);
                    } else {
                        final boolean downgrade = PROTO_TCP_CONNECT.equals(subprotocol);
                        try {
                            if (!handshake(tunnelKey, handshakePayload, downgrade, ctx)) {
                                ctx.writeAndFlush(new CloseWebSocketFrame(WebSocketCloseStatus.INVALID_PAYLOAD_DATA)).addListener(ChannelFutureListener.CLOSE);
                            }
                        } finally {
                            handshakePayload.release();
                        }
                    }
                }
            } else if (PROTO_AGENT_BACKHAUL.equals(subprotocol)) {
                /*-
                 * BACKHAUL {endpoint}/{tunnelKey}/{connectionId}.
                 */
                final String tunnelKeyAndConnectionId = getPathWithinEndpoint(handshake);
                final int i = tunnelKeyAndConnectionId.indexOf('/');
                final String tunnelKey = 0 <= i ? tunnelKeyAndConnectionId.substring(0, i) : null;
                final String connectionId = tunnelKeyAndConnectionId.substring(i + 1);
                if (null == tunnelKey || tunnelKey.isEmpty() || connectionId.isEmpty()) {
                    ctx.writeAndFlush(new CloseWebSocketFrame(WebSocketCloseStatus.ENDPOINT_UNAVAILABLE)).addListener(ChannelFutureListener.CLOSE);
                } else if (!finishHandshake(ctx, tunnelKey, connectionId)) {
                    ctx.writeAndFlush(new CloseWebSocketFrame(WebSocketCloseStatus.ABNORMAL_CLOSURE)).addListener(ChannelFutureListener.CLOSE);
                }
            } else if (PROTO_MGR_CONSOLE.equals(subprotocol)) {
                if (!isConsoleAllowed(handshake)) {
                    ctx.writeAndFlush(new CloseWebSocketFrame(WebSocketCloseStatus.POLICY_VIOLATION)).addListener(ChannelFutureListener.CLOSE);
                } else {
                    ctx.pipeline().replace(ctx.name(), null, new WebSocketBridgeServerConsoleHandler(
                            webSocketBridgeServerEngine, webSocketBridgeServerForwarder
                    ));
                }
            } else {
                log.warn("Connection aborted: invalid protocol '{}' from {}", rawSubprotocol, ctx.channel().remoteAddress());
                ctx.writeAndFlush(new CloseWebSocketFrame(WebSocketCloseStatus.PROTOCOL_ERROR)).addListener(ChannelFutureListener.CLOSE);
            }
        }
        ctx.fireUserEventTriggered(evt);
    }

    private boolean isConsoleAllowed(final HandshakeComplete handshake) {
        /*
        final String accessToken = Util.last(new QueryStringDecoder(handshake.requestUri()).parameters(), "access_token");
        return null != accessToken && !accessToken.isEmpty() && accessToken.equals(System.getProperty("access_token"));
         */
        return true;
    }

    private String getPathWithinEndpoint(final HandshakeComplete handshake) {
        final URI endpointPathUri = URI.create(new QueryStringDecoder(endpointPath).path());
        final URI handshakePathUri = URI.create(new QueryStringDecoder(handshake.requestUri()).path());
        return endpointPathUri.relativize(handshakePathUri).getPath();
    }

    private ByteBuf getHandshakePayload(final HandshakeComplete handshake) {
        final String authorization = handshake.requestHeaders().get("Authorization");
        final String payload;
        if (null != authorization) {
            payload = authorization.startsWith("Bearer ") ? authorization.substring(7) : null;
        } else {
            payload = Util.last(new QueryStringDecoder(handshake.requestUri()).parameters(), "access_token");
        }
        if (null == payload || payload.isEmpty()) {
            return null;
        }

        final ByteBuf encoded = Unpooled.wrappedBuffer(CharsetUtil.UTF_8.encode(payload));
        try {
            return Base64.decode(encoded, Base64Dialect.URL_SAFE);
        } finally {
            encoded.release();
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void exceptionCaught(final ChannelHandlerContext ctx, final Throwable cause) throws Exception {
        log.error("Connection aborted: {}", cause.getMessage(), cause);
        final WebSocketCloseStatus closeStatus = WebSocketCloseStatus.PROTOCOL_ERROR;
        ctx.writeAndFlush(new CloseWebSocketFrame(closeStatus, cause.getMessage())).addListener(ChannelFutureListener.CLOSE);
    }

    /* **** */

    private static InetSocketAddress parseSocketAddress(final ByteBuf in) throws UnknownHostException {
        final byte addressType = in.readByte();
        if (ATYPE_IPv4 == addressType) {
            final byte[] addr = ByteBufUtil.getBytes(in.readBytes(IPv4_ADDR_SIZE));
            return new InetSocketAddress(InetAddress.getByAddress(addr), in.readUnsignedShort());
        } else if (ATYPE_DOMAIN == addressType) {
            final String domain = in.readCharSequence(in.readUnsignedByte(), CharsetUtil.UTF_8).toString();
            return InetSocketAddress.createUnresolved(domain, in.readUnsignedShort());
        } else if (ATYPE_IPv6 == addressType) {
            final byte[] addr = ByteBufUtil.getBytes(in.readBytes(IPv6_ADDR_SIZE));
            return new InetSocketAddress(InetAddress.getByAddress(addr), in.readUnsignedShort());
        }
        throw new UnknownHostException("address type: " + addressType);
    }

    /**
     * Register an agent.
     *
     * @param tunnelKey the tunnel key
     * @param payload   the request payload
     * @param agentCtx  the agent channel context
     * @return <code>true</code> if the agent is registered; otherwise <code>false</code>
     * @throws UnknownHostException if the address type is not supported
     */
    private boolean registerAgent(final String tunnelKey, final ByteBuf payload,
                                  final ChannelHandlerContext agentCtx) throws UnknownHostException {
        /*-
         * The agent registration request is a SOCKS5-like request:
         *
         * +-----+-------+-------+------+----------+----------+----------+----------+
         * | VER |  CMD  |  RSV  | ATYP | BND.ADDR | BND.PORT | AGN.NAME |  AGN.VER |
         * +-----+-------+-------+------+----------+----------+----------+----------+
         * |  1  | x'FF' | X'00' |  1   | Variable |    2     | Variable | Variable |
         * +-----+-------+-------+------+----------+----------+----------+----------+
         */
        final byte version = payload.readByte();
        if (VER_1 != version) {
            log.debug("unsupported version: {}, (expected: {})", version, VER_1);
            return false;
        }

        final byte cmd = payload.readByte();
        if (CMD_SERVICE != cmd) {
            log.debug("unsupported command type: {}, (expected: {})", cmd, CMD_SERVICE);
            return false;
        }

        final byte rsv = payload.readByte();
        if (RSV != rsv) {
            log.debug("unsupported rsv: {}, (expected: {})", rsv, RSV);
            return false;
        }

        final InetSocketAddress intranet = parseSocketAddress(payload);
        final String agentName = payload.readCharSequence(payload.readUnsignedByte(), CharsetUtil.UTF_8).toString();
        final String agentVersion = payload.readCharSequence(payload.readUnsignedByte(), CharsetUtil.UTF_8).toString();
        return registerAgent0(tunnelKey, version, agentName, agentVersion, intranet.getHostString(), agentCtx);
    }

    /**
     * Register an agent.
     *
     * @param tunnelKey     the tunnel key
     * @param tunnelVersion the tunnel version
     * @param agentName     the agent name
     * @param agentVersion  the agent version
     * @param intranet      the agent intranet address
     * @param agentCtx      the agent channel context
     * @return <code>true</code> if the agent is registered; otherwise <code>false</code>
     */
    private boolean registerAgent0(final String tunnelKey, final byte tunnelVersion,
                                   final String agentName, final String agentVersion,
                                   final String intranet, final ChannelHandlerContext agentCtx) {
        final boolean registered = webSocketBridgeServerEngine.registerAgent(
                tunnelKey, tunnelVersion, agentName, agentVersion, intranet, agentCtx
        );
        if (registered) {
            agentCtx.pipeline().replace(agentCtx.name(), null, new WebSocketKeepaliveHandler(60, 60, 60) {
                /**
                 * {@inheritDoc}
                 */
                @Override
                public void channelRead(final ChannelHandlerContext ctx, final Object msg) {
                    /*-
                     * Handle an agent registration response.
                     */
                    if (msg instanceof BinaryWebSocketFrame) {
                        try {
                            webSocketBridgeServerEngine.agentResponded(((BinaryWebSocketFrame) msg).content(), ctx);
                        } finally {
                            ReferenceCountUtil.release(msg);
                        }
                    } else {
                        ctx.fireChannelRead(msg);
                    }
                }
            });
        }
        return registered;
    }

    /**
     * Initiate a handshake request to establish a WebSocket connection or downgrade it to a TCP socket.
     *
     * @param tunnelKey the tunnel key
     * @param payload   the request payload
     * @param downgrade whether to downgrade the WebSocket to a TCP socket
     * @param accessCtx the access channel context
     * @return <code>true</code> if the handshake is initiated; otherwise <code>false</code>
     * @throws UnknownHostException if the address type is not supported
     */
    private boolean handshake(final String tunnelKey, final ByteBuf payload,
                              final boolean downgrade, final ChannelHandlerContext accessCtx) throws UnknownHostException {
        /*-
         * The TCP connect request is a SOCKS5-like request:
         *
         * +-----+-----+-------+------+----------+----------+
         * | VER | CMD |  RSV  | ATYP | DST.ADDR | DST.PORT |
         * +-----+-----+-------+------+----------+----------+
         * |  1  |  1  | X'00' |  1   | Variable |    2     |
         * +-----+-----+-------+------+----------+----------+
         */
        final byte version = payload.readByte();
        if (VER_1 != version) {
            log.debug("unsupported version: {}, (expected: {})", version, VER_1);
            return false;
        }

        final byte cmd = payload.readByte();
        if (CMD_CONNECT != cmd) {
            log.debug("unsupported command type: {}, (expected: {})", cmd, CMD_CONNECT);
            return false;
        }

        final byte rsv = payload.readByte();
        if (RSV != rsv) {
            log.debug("unsupported rsv: {}, (expected: {})", rsv, RSV);
            return false;
        }


        final InetSocketAddress target = parseSocketAddress(payload);
        return handshake0(tunnelKey, target, downgrade, accessCtx);
    }

    /**
     * Initiate a handshake to establish a WebSocket connection or downgrade it to a TCP socket.
     *
     * @param tunnelKey the tunnel key
     * @param target    the target address
     * @param downgrade whether to downgrade the WebSocket to a TCP socket
     * @param accessCtx the access channel context
     */
    private boolean handshake0(final String tunnelKey, final InetSocketAddress target, final boolean downgrade, final ChannelHandlerContext accessCtx) {
        final String id = accessCtx.channel().id().toString();
        final SocketAddress source = accessCtx.channel().remoteAddress();
        log.info("[{}] Establishing WebSocket connection from {} to {} via {}", id, source, target, tunnelKey);

        accessCtx.channel().config().setAutoRead(false);
        webSocketBridgeServerEngine.handshake(accessCtx, tunnelKey, target).addListener(new FutureListener<ChannelHandlerContext>() {
            @Override
            public void operationComplete(final Future<ChannelHandlerContext> backhaulFuture) throws Exception {
                if (backhaulFuture.isSuccess()) {
                    final ChannelHandlerContext backhaulCtx = backhaulFuture.getNow();
                    backhaulCtx.channel().config().setAutoRead(false);
                    backhaulCtx.channel().closeFuture().addListener(new ChannelFutureListener() {
                        @Override
                        public void operationComplete(final ChannelFuture future) throws Exception {
                            if (accessCtx.channel().isActive()) {
                                log.info("[{}] WebSocket connection closed by agent: from {} to {} via {}", id, source, target, tunnelKey);
                            } else {
                                log.info("[{}] WebSocket connection closed by client: from {} to {} via {}", id, source, target, tunnelKey);
                            }
                        }
                    });

                    log.info("[{}] WebSocket connection established from {} to {} via {}", id, source, target, tunnelKey);

                    backhaulCtx.pipeline().addBefore(backhaulCtx.name(), "backhaul-keepalive", new WebSocketKeepaliveHandler(600, 600, 600));

                    if (!downgrade) {
                        /*-
                         * client <--ws--> server <--ws--> agent
                         */
                        accessCtx.pipeline().replace(accessCtx.name(), null, new WebSocketInboundRedirectHandler(backhaulCtx));
                        backhaulCtx.pipeline().replace(backhaulCtx.name(), null, new WebSocketInboundRedirectHandler(accessCtx));
                    } else {
                        /*-
                         * client <--ws--> server <--ws--> agent
                         * downgrade to:
                         * client <--socket--> server <--ws--> agent
                         */
                        accessCtx.pipeline().replace(accessCtx.name(), null, new TcpOverWebSocketEncodeHandler(backhaulCtx));
                        backhaulCtx.pipeline().replace(backhaulCtx.name(), null, new TcpOverWebSocketDecodeHandler(accessCtx));

                        /*-
                         * Remove the WebSocket codec.
                         *
                         * @see io.netty.handler.codec.http.websocketx.WebSocketServerHandshaker#handshake
                         */
                        accessCtx.pipeline().remove("wsencoder");
                        accessCtx.pipeline().remove("wsdecoder");
                        // accessCtx.pipeline().remove("WS403Responder");
                        accessCtx.pipeline().remove(Utf8FrameValidator.class);
                        accessCtx.pipeline().remove(WebSocketServerProtocolHandler.class);
                    }

                    accessCtx.channel().config().setAutoRead(true);
                    backhaulCtx.channel().config().setAutoRead(true);
                } else {
                    final Throwable cause = backhaulFuture.cause();

                    log.warn("[{}] WebSocket connection from {} to {} via {} failed: {}", id, source, target, tunnelKey, cause.getMessage());

                    accessCtx.writeAndFlush(
                            new CloseWebSocketFrame(WebSocketCloseStatus.ENDPOINT_UNAVAILABLE, cause.getMessage())
                    ).addListener(ChannelFutureListener.CLOSE);
                }
            }
        });
        return true;
    }

    /**
     * Validate and finish the opening handshake initiated by {@link #handshake}.
     *
     * @param tunnelKey    the tunnel key
     * @param connectionId the connection id
     * @param backhaulCtx  the backhaul channel context
     * @return true if the handshake completes successfully; otherwise false
     */
    private boolean finishHandshake(final ChannelHandlerContext backhaulCtx,
                                    final String tunnelKey, final String connectionId) {
        return webSocketBridgeServerEngine.finishHandshake(tunnelKey, connectionId, backhaulCtx);
    }

}

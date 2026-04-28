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
import io.netty.handler.codec.http.QueryStringDecoder;
import io.netty.handler.codec.http.websocketx.*;
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolHandler.HandshakeComplete;
import io.netty.util.CharsetUtil;
import io.netty.util.ReferenceCountUtil;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.FutureListener;
import lombok.extern.slf4j.Slf4j;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.UnknownHostException;
import java.security.MessageDigest;

import static com.github.pangolin.util.Constants.*;

/**
 *
 */
@Slf4j
public class WebSocketBridgeServerHandler extends ChannelInboundHandlerAdapter {
    /**
     * <pre>
     * +-----+--------+-------+------+------+------+--------------+--------+---------+-----------+
     * | VER | CMD/   | RSV   | ATYP | ADDR | PORT |  extras      | TS (8) | NONCE(8)| HMAC (32) |
     * |     |  REP   |       |      |      |      |              |        |         |           |
     * +-----+--------+-------+------+------+------+--------------+--------+---------+-----------+
     * | 1   | 1      | 1     | 1    | var  | 2    | var          | 8      | 8       | 32        |
     * +-----+--------+-------+------+------+------+--------------+--------+---------+-----------+
     *                                                            └── auth tail(only handshake payload) ──┘
     * </pre>
     */
    private static final int MIN_TRUST_PAYLOAD_LENGTH = 1 + 1 + 1 + 1 + IPv4_ADDR_SIZE + 2;

    private static final int PAYLOAD_TS_SIZE = 8;
    private static final int PAYLOAD_NONCE_SIZE = 8;
    private static final int PAYLOAD_HMAC_SIZE = 32;
    private static final int MIN_NOT_TRUST_PAYLOAD_LENGTH = MIN_TRUST_PAYLOAD_LENGTH + PAYLOAD_TS_SIZE + PAYLOAD_NONCE_SIZE + PAYLOAD_HMAC_SIZE;

    private static final int MAX_TIMESTAMP_DIFF_SECONDS = 30;

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
                    try {
                        final WebSocketCloseStatus status = registerAgent(tunnelKey, handshakePayload, ctx);
                        if (null != status) {
                            ctx.writeAndFlush(new CloseWebSocketFrame(status)).addListener(ChannelFutureListener.CLOSE);
                        }
                    } finally {
                        handshakePayload.release();
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
                    final boolean downgrade = PROTO_TCP_CONNECT.equals(subprotocol);
                    try {
                        final WebSocketCloseStatus status = handshake(tunnelKey, handshakePayload, downgrade, ctx);
                        if (null != status) {
                            ctx.writeAndFlush(new CloseWebSocketFrame(status)).addListener(ChannelFutureListener.CLOSE);
                        }
                    } finally {
                        handshakePayload.release();
                    }
                }
            } else if (PROTO_AGENT_BACKHAUL.equals(subprotocol)) {
                /*-
                 * BACKHAUL {endpoint}/{tunnelKey}.
                 */
                final String tunnelKey = getPathWithinEndpoint(handshake);
                if (null == tunnelKey || tunnelKey.isEmpty()) {
                    ctx.writeAndFlush(new CloseWebSocketFrame(WebSocketCloseStatus.ENDPOINT_UNAVAILABLE)).addListener(ChannelFutureListener.CLOSE);
                } else {
                    final ByteBuf handshakePayload = getHandshakePayload(handshake);
                    try {
                        final WebSocketCloseStatus status = finishHandshake(ctx, tunnelKey, handshakePayload);
                        if (null != status) {
                            ctx.writeAndFlush(new CloseWebSocketFrame(status)).addListener(ChannelFutureListener.CLOSE);
                        }
                    } finally {
                        handshakePayload.release();
                    }
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

    private String getPathWithinEndpoint(final HandshakeComplete handshake) {
        final String basePath = new QueryStringDecoder(endpointPath).path();
        final String requestPath = new QueryStringDecoder(handshake.requestUri()).path();
        final String prefix = basePath.endsWith("/") ? basePath : basePath + "/";

        if (!requestPath.startsWith(prefix)) {
            return null;
        }

        final String tunnelKey = requestPath.substring(prefix.length());
        return !tunnelKey.isEmpty() && tunnelKey.indexOf('/') < 0 ? tunnelKey : null;
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
            return Unpooled.EMPTY_BUFFER;
        }
        return Util.urlSafeBase64Decode(payload);
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

    /**
     * Register an agent.
     *
     * @param tunnelKey the tunnel key
     * @param payload   the request payload
     * @param agentCtx  the agent channel context
     * @return <code>true</code> if the agent is registered; otherwise <code>false</code>
     * @throws UnknownHostException if the address type is not supported
     */
    private WebSocketCloseStatus registerAgent(final String tunnelKey, ByteBuf payload,
                                               final ChannelHandlerContext agentCtx) throws UnknownHostException {
        /*-
         * The agent registration request is a SOCKS5-like request:
         *
         * +-----+-----+-------+------+----------+----------+----------+----+-------+------+
         * | VER | CMD |  RSV  | ATYP | BND.ADDR | BND.PORT | AGN.INFO | TS | NONCE | HMAC |
         * +-----+-----+-------+------+----------+----------+----------+----+-------+------+
         * |  1  |  1  | X'00' |  1   | Variable |    2     | Variable |  8 |   8   |  32  |
         * +-----+-----+-------+------+----------+----------+----------+----+------ +------+
         */
        WebSocketCloseStatus error = checkPayload(tunnelKey, payload);
        if (null == error) {
            final byte version = payload.readByte();
            if (VER_1 != version) {
                log.debug("[AGENT] unsupported version: {}, (expected: {})", version, VER_1);
                return WebSocketCloseStatus.PROTOCOL_ERROR;
            }

            final byte cmd = payload.readByte();
            if (CMD_SERVICE != cmd) {
                log.debug("[AGENT] unsupported command type: {}, (expected: {})", cmd, CMD_SERVICE);
                return WebSocketCloseStatus.PROTOCOL_ERROR;
            }

            final byte rsv = payload.readByte();
            if (RSV != rsv) {
                log.debug("[AGENT] unsupported rsv: {}, (expected: {})", rsv, RSV);
                return WebSocketCloseStatus.PROTOCOL_ERROR;
            }

            final InetSocketAddress intranet = Util.readSocketAddress(payload, false);
            final String agentName = payload.readString(payload.readUnsignedByte(), CharsetUtil.UTF_8);
            if (!registerAgent0(tunnelKey, agentName, intranet.getHostString(), agentCtx)) {
                return WebSocketCloseStatus.POLICY_VIOLATION;
            }
        }
        return error;

    }

    /**
     * Register an agent.
     *
     * @param tunnelKey the tunnel key
     * @param agentName the agent name
     * @param intranet  the agent intranet address
     * @param agentCtx  the agent channel context
     * @return <code>true</code> if the agent is registered; otherwise <code>false</code>
     */
    private boolean registerAgent0(final String tunnelKey, final String agentName,
                                   final String intranet, final ChannelHandlerContext agentCtx) {
        final boolean registered = webSocketBridgeServerEngine.registerAgent(
                tunnelKey, agentName, intranet, agentCtx
        );
        if (registered) {
            agentCtx.pipeline().replace(agentCtx.name(), null, new WebSocketKeepaliveHandler(60, 60, 60) {
                /**
                 * {@inheritDoc}
                 */
                @Override
                public void channelRead(final ChannelHandlerContext ctx, final Object msg) throws Exception {
                    /*-
                     * Handle an agent registration response.
                     */
                    if (msg instanceof BinaryWebSocketFrame) {
                        try {
                            webSocketBridgeServerEngine.agentResponded(tunnelKey, ((BinaryWebSocketFrame) msg).content(), ctx);
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
    private WebSocketCloseStatus handshake(final String tunnelKey, ByteBuf payload,
                                           final boolean downgrade, final ChannelHandlerContext accessCtx) throws UnknownHostException {
        /*-
         * The TCP connect request is a SOCKS5-like request:
         *
         * +-----+-----+-------+------+----------+----------+-----------+----+-------+------+
         * | VER | CMD |  RSV  | ATYP | DST.ADDR | DST.PORT |  EXT.INFO | TS | NONCE | HMAC |
         * +-----+-----+-------+------+----------+----------+-----------+----+-------+------+
         * |  1  |  1  | X'00' |  1   | Variable |    2     |  Variable | 8  |   8   |  32  |
         * +-----+-----+-------+------+----------+----------+-----------+----+------ +------+
         */
        WebSocketCloseStatus error = checkPayload(tunnelKey, payload);
        if (null == error) {
            final byte version = payload.readByte();
            if (VER_1 != version) {
                log.debug("[HANDSHAKE] unsupported version: {}, (expected: {})", version, VER_1);
                return WebSocketCloseStatus.PROTOCOL_ERROR;
            }

            final byte cmd = payload.readByte();
            if (CMD_CONNECT != cmd) {
                log.debug("[HANDSHAKE] unsupported command type: {}, (expected: {})", cmd, CMD_CONNECT);
                return WebSocketCloseStatus.PROTOCOL_ERROR;
            }

            final byte rsv = payload.readByte();
            if (RSV != rsv) {
                log.debug("[HANDSHAKE] unsupported rsv: {}, (expected: {})", rsv, RSV);
                return WebSocketCloseStatus.PROTOCOL_ERROR;
            }

            final InetSocketAddress target = Util.readSocketAddress(payload, false);
            if (!handshake0(tunnelKey, target, downgrade, accessCtx)) {
                return WebSocketCloseStatus.POLICY_VIOLATION;
            }
        }
        return error;
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

                    backhaulCtx.pipeline().addBefore(backhaulCtx.name(), "backhaul-keepalive", new WebSocketKeepaliveHandler(60, 60, 60));

                    if (!downgrade) {
                        /*-
                         * client <--ws--> server <--ws--> agent
                         */
                        accessCtx.pipeline().addBefore(accessCtx.name(), "access-keepalive", new WebSocketKeepaliveHandler(60, 60, 60));
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
     * @param tunnelKey   the tunnel key
     * @param backhaulCtx the backhaul channel context
     * @return true if the handshake completes successfully; otherwise false
     */
    private WebSocketCloseStatus finishHandshake(final ChannelHandlerContext backhaulCtx,
                                                 final String tunnelKey, final ByteBuf payload) throws UnknownHostException {
        /*-
         * The TCP connect request is a SOCKS5-like request:
         *
         * +-----+-----+-------+------+----------+----------+-----------+----+-------+------+
         * | VER | REP |  RSV  | ATYP | DST.ADDR | DST.PORT |    ID     | TS | NONCE | HMAC |
         * +-----+-----+-------+------+----------+----------+-----------+----+-------+------+
         * |  1  |  1  | X'00' |  1   | Variable |    2     |  Variable | 8  |   8   |  32  |
         * +-----+-----+-------+------+----------+----------+-----------+----+------ +------+
         */
        WebSocketCloseStatus error = checkPayload(tunnelKey, payload);
        if (null == error) {
            final byte version = payload.readByte();
            if (VER_1 != version) {
                log.debug("[BACKHAUL] unsupported version: {}, (expected: {})", version, VER_1);
                return WebSocketCloseStatus.PROTOCOL_ERROR;
            }

            final byte status = payload.readByte();
            if (REPLY_SUCCESS != status) {
                log.debug("[BACKHAUL] unsupported status: {}, (expected: {})", status, REPLY_SUCCESS);
                return WebSocketCloseStatus.PROTOCOL_ERROR;
            }

            final byte rsv = payload.readByte();
            if (RSV != rsv) {
                log.debug("[BACKHAUL] unsupported rsv: {}, (expected: {})", rsv, RSV);
                return WebSocketCloseStatus.PROTOCOL_ERROR;
            }

            Util.skipSocketAddress(payload);

            final String connectionId = payload.readString(payload.readUnsignedByte(), CharsetUtil.UTF_8);
            if (!webSocketBridgeServerEngine.finishHandshake(tunnelKey, connectionId, backhaulCtx)) {
                return WebSocketCloseStatus.POLICY_VIOLATION;
            }
        }
        return error;
    }

    private boolean isConsoleAllowed(final HandshakeComplete handshake) {
        final String accessToken = Util.last(new QueryStringDecoder(handshake.requestUri()).parameters(), "access_token");
        return null != accessToken && !accessToken.isEmpty() && accessToken.equals(System.getProperty("websocket.bridge.console.access_token"));
    }

    private WebSocketCloseStatus checkPayload(final String tunnelKey, final ByteBuf payload) {
        final int len = null != payload ? payload.readableBytes() : 0;
        if (len < MIN_NOT_TRUST_PAYLOAD_LENGTH) {
            return WebSocketCloseStatus.INVALID_PAYLOAD_DATA;
        }

        final int offset = payload.readerIndex();
        final byte[] expected = ByteBufUtil.getBytes(payload, offset + len - PAYLOAD_HMAC_SIZE, PAYLOAD_HMAC_SIZE);
        final byte[] computed = webSocketBridgeServerEngine.hmac(tunnelKey, payload, offset, len - PAYLOAD_HMAC_SIZE);
        if (null == computed || 0 == computed.length || !MessageDigest.isEqual(expected, computed)) {
            return WebSocketCloseStatus.POLICY_VIOLATION;
        }

        final long timestamp = payload.getLong(len - PAYLOAD_HMAC_SIZE - PAYLOAD_NONCE_SIZE - PAYLOAD_TS_SIZE);
        if (Math.abs(System.currentTimeMillis() / 1000 - timestamp) > MAX_TIMESTAMP_DIFF_SECONDS) {
            return WebSocketCloseStatus.POLICY_VIOLATION;
        }

        // TODO check nonce

        return null;
    }

}

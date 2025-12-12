package com.github.pangolin.server;

import com.github.pangolin.handler.TcpOverWebSocketDecodeHandler;
import com.github.pangolin.handler.TcpOverWebSocketEncodeHandler;
import com.github.pangolin.handler.WebSocketInboundRedirectHandler;
import com.github.pangolin.server.shell.WebSocketBridgeServerConsoleHandler;
import com.github.pangolin.util.Util;
import com.google.common.base.Preconditions;
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

/**
 *
 */
@Slf4j
public class WebSocketBridgeServerHandler extends ChannelInboundHandlerAdapter {
    /**
     * @see #PROTO_AGENT_SERVICE
     * @deprecated
     */
    @Deprecated
    private static final String PROTO_AGENT_REGISTER = "PASSIVE-REG";

    /**
     * @see #PROTO_AGENT_BACKHAUL
     * @deprecated
     */
    @Deprecated
    private static final String PROTO_AGENT_BACKHAUL_LEGACY = "PASSIVE";

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

    /**
     * .
     */
    private static final String PARAM_BACKHAUL_ID = "id";

    private final String endpointPath;
    private final String accessKey;
    private final WebSocketBridgeServerEngine engine;
    private final WebSocketBridgeServerForwarder forwarder;

    public WebSocketBridgeServerHandler(final String endpointPath,
                                        final WebSocketBridgeServerEngine engine,
                                        final WebSocketBridgeServerForwarder forwarder) {
        this(endpointPath,
                System.getProperty("websocket.bridge.access_key", "c254dacd0cde3be75ac2988f691ec105"),
                engine, forwarder);
    }

    public WebSocketBridgeServerHandler(final String endpointPath,
                                        final String accessKey,
                                        final WebSocketBridgeServerEngine engine,
                                        final WebSocketBridgeServerForwarder forwarder) {
        this.endpointPath = endpointPath;
        this.accessKey = accessKey;
        this.engine = engine;
        this.forwarder = forwarder;
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

            if (PROTO_AGENT_REGISTER.equals(subprotocol) || PROTO_AGENT_SERVICE.equals(subprotocol)) {
                /*-
                 * agent register.
                 *
                 * TODO {endpoint}/{tunnel}
                 *  FIXME authenticate
                 */
                if (!engine.agentRegistered(handshake, ctx)) {
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
                            /*-
                             * agent service CMD response.
                             */
                            if (msg instanceof BinaryWebSocketFrame) {
                                try {
                                    engine.agentResponded((BinaryWebSocketFrame) msg, ctx);
                                } finally {
                                    ReferenceCountUtil.release(msg);
                                }
                            } else {
                                ctx.fireChannelRead(msg);
                            }
                        }
                    });
                }
            } else if (PROTO_WS_CONNECT.equals(subprotocol) || PROTO_TCP_CONNECT.equals(subprotocol)) {
                final boolean downgrade = PROTO_TCP_CONNECT.equals(subprotocol);
                /*-
                 * CONNECT request.
                 *
                 * {endpoint}/{[agent@]tunnel}
                 */
                final String tunnelKey = getPathWithinEndpoint(handshake);
                final String accessToken = getHandshakeAccessToken(handshake);

                if (null == tunnelKey) {
                    ctx.writeAndFlush(new CloseWebSocketFrame(WebSocketCloseStatus.ENDPOINT_UNAVAILABLE)).addListener(ChannelFutureListener.CLOSE);
                } else if (null == accessToken) {
                    ctx.writeAndFlush(new CloseWebSocketFrame(WebSocketCloseStatus.POLICY_VIOLATION)).addListener(ChannelFutureListener.CLOSE);
                } else if (!handshake(ctx, tunnelKey, accessToken, downgrade)) {
                    ctx.writeAndFlush(new CloseWebSocketFrame(WebSocketCloseStatus.INVALID_PAYLOAD_DATA)).addListener(ChannelFutureListener.CLOSE);
                }
            } else if (PROTO_AGENT_BACKHAUL_LEGACY.equals(subprotocol) || PROTO_AGENT_BACKHAUL.equals(subprotocol)) {
                /*-
                 * BACKHAUL request.
                 *
                 * {endpoint}/{handshake_id}
                 */
                String id = getPathWithinEndpoint(handshake);
                // @Deprecated
                id = null == id || id.isEmpty() ? Util.last(new QueryStringDecoder(handshake.requestUri()).parameters(), PARAM_BACKHAUL_ID) : id;
                if (null == id || id.isEmpty() || !engine.finishHandshake(id, ctx)) {
                    ctx.writeAndFlush(new CloseWebSocketFrame(WebSocketCloseStatus.POLICY_VIOLATION)).addListener(ChannelFutureListener.CLOSE);
                }
            } else if (PROTO_MGR_CONSOLE.equals(subprotocol)) {
                // XXX authorize.
                //  FIXME authenticate
                ctx.pipeline().replace(ctx.name(), null, new WebSocketBridgeServerConsoleHandler(
                        engine, forwarder
                ));
            } else {
                ctx.writeAndFlush(new CloseWebSocketFrame(WebSocketCloseStatus.PROTOCOL_ERROR)).addListener(ChannelFutureListener.CLOSE);
            }
        }
        ctx.fireUserEventTriggered(evt);
    }

    private String getPathWithinEndpoint(final HandshakeComplete handshake) {
        final URI endpointPathUri = URI.create(new QueryStringDecoder(endpointPath).path());
        final URI handshakePathUri = URI.create(new QueryStringDecoder(handshake.requestUri()).path());
        return endpointPathUri.relativize(handshakePathUri).getPath();
    }

    private String getHandshakeAccessToken(final HandshakeComplete handshake) {
        final String authorization = handshake.requestHeaders().get("Authorization");
        if (null != authorization) {
            return authorization.startsWith("Bearer ") ? authorization.substring(7) : null;
        }

        // @Deprecated
        return Util.last(new QueryStringDecoder(handshake.requestUri()).parameters(), "access_token");
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void exceptionCaught(final ChannelHandlerContext ctx, final Throwable cause) throws Exception {
        log.error("Connection aborted: {}", cause.getMessage(), cause);
        ctx.writeAndFlush(new CloseWebSocketFrame(
                WebSocketCloseStatus.INTERNAL_SERVER_ERROR, cause.getMessage()
        )).addListener(ChannelFutureListener.CLOSE);
    }

    private boolean handshake(final ChannelHandlerContext accessCtx,
                              final String tunnelKey, final String accessToken,
                              final boolean downgrade) throws UnknownHostException {
        final ByteBuf in = Base64.decode(Unpooled.wrappedBuffer(accessToken.getBytes()), Base64Dialect.URL_SAFE);
        /*-
         * TCP connect request is a SOCKS5-like request:
         *
         * +-----+----------+-----+-------+------+----------+----------+
         * | VER |AccessKey | CMD |  RSV  | ATYP | DST.ADDR | DST.PORT |
         * +-----+----------+-----+-------+------+----------+----------+
         * |  1  | Variable |  1  | X'00' |  1   | Variable |    2     |
         * +-----+----------+-----+-------+------+----------+----------+
         */
        final byte version = in.readByte();
        Preconditions.checkState(VER_1 == version, "unsupported version: %s, (expected: %s)", version, VER_1);

        final String accessKey = in.readCharSequence(in.readUnsignedByte(), CharsetUtil.UTF_8).toString();

        final byte cmd = in.readByte();
        Preconditions.checkState(CMD_CONNECT == cmd, "unsupported command type: %s, (expected: %s)", cmd, CMD_CONNECT);

        final byte rsv = in.readByte();
        Preconditions.checkState(0 == rsv, "unsupported rsv: %s, (expected: %s)", rsv, 0);

        final InetSocketAddress target = parseSocketAddress(in);
        if (!authenticate(accessKey)) {
            return false;
        }
        // ..
        handshake0(accessCtx, tunnelKey, target, downgrade);
        return true;
    }

    private boolean authenticate(final String accessKey) {
        final String accessTokenInSys = this.accessKey;
        if (null == accessTokenInSys || accessTokenInSys.isEmpty()) {
            return true;
        }
        return accessTokenInSys.equals(accessKey);
    }


    private static final byte IPv4_ADDR_SIZE = 4;
    private static final byte IPv6_ADDR_SIZE = 16;

    private static final byte VER_1 = 0x01;

    private static final byte CMD_CONNECT = 0x01;

    private static final byte ATYPE_IPv4 = 0x01;
    private static final byte ATYPE_DOMAIN = 0x03;
    private static final byte ATYPE_IPv6 = 0x04;

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
     * Initiate a handshake with the agent for target address to establish
     * a WebSocket connection or downgrade the WebSocket to a TCP socket.
     *
     * @param accessCtx the access channel context
     * @param tunnelKey the connection tunnel key
     * @param target    the connection target address
     * @param downgrade the WebSocket downgrade to a TCP socket
     */
    private void handshake0(final ChannelHandlerContext accessCtx,
                            final String tunnelKey, final InetSocketAddress target, final boolean downgrade) {
        final String id = accessCtx.channel().id().toString();
        final SocketAddress source = accessCtx.channel().remoteAddress();
        log.info("[{}] Establishing WebSocket connection from {} to {} via {}", id, source, target, tunnelKey);

        accessCtx.channel().config().setAutoRead(false);
        engine.handshake(
                accessCtx, tunnelKey, target, accessCtx.executor().newPromise()
        ).addListener(new FutureListener<ChannelHandlerContext>() {
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

                    if (!downgrade) {
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

                    log.warn("[{}] WebSocket connection from {} to {} via {} failed: {}", id, source, target, tunnelKey, cause.getMessage());

                    accessCtx.writeAndFlush(
                            new CloseWebSocketFrame(WebSocketCloseStatus.ENDPOINT_UNAVAILABLE, cause.getMessage())
                    ).addListener(ChannelFutureListener.CLOSE);
                }
            }
        });
    }

}

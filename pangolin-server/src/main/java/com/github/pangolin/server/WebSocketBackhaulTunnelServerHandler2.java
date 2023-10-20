package com.github.pangolin.server;

import com.github.pangolin.handler.WebSocketInboundRedirectHandler;
import com.github.pangolin.handler.WebSocketServerHandshakeNegotiationHandler;
import com.github.pangolin.util.Util;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.handler.codec.http.EmptyHttpHeaders;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.QueryStringDecoder;
import io.netty.handler.codec.http.websocketx.CloseWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketCloseStatus;
import io.netty.handler.codec.http.websocketx.WebSocketServerHandshaker;
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolHandler;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.FutureListener;
import io.netty.util.concurrent.Promise;
import lombok.extern.slf4j.Slf4j;

import java.net.URI;
import java.util.List;
import java.util.Map;

/**
 */
@Slf4j
class WebSocketBackhaulTunnelServerHandler2 extends WebSocketServerHandshakeNegotiationHandler {
    private static final String PROTOCOL_AGENT_REGISTER = "PASSIVE-REG";
    private static final String PROTOCOL_WS_TUNNEL_REQUEST = "";
    private static final String PROTOCOL_TCP_TUNNEL_REQUEST = "CONNECT";
    private static final String PROTOCOL_TUNNEL_BACKHAUL = "PASSIVE";
    private static final String PROTOCOL_MGR_CONSOLE = "CONSOLE";

    private final WebSocketBackhaulTunnelServerEngine webSocketBackhaulTunnelServerEngine;
    private final WebSocketBackhaulTunnelServerForwarder webSocketBackhaulTunnelServerForwarder;

    public WebSocketBackhaulTunnelServerHandler2(final String webSocketPath, final String subprotocols,
                                                 final WebSocketBackhaulTunnelServerEngine webSocketBackhaulTunnelServerEngine,
                                                 final WebSocketBackhaulTunnelServerForwarder webSocketBackhaulTunnelServerForwarder) {
        super(webSocketPath, subprotocols, true, 65536, true, true);
        this.webSocketBackhaulTunnelServerEngine = webSocketBackhaulTunnelServerEngine;
        this.webSocketBackhaulTunnelServerForwarder = webSocketBackhaulTunnelServerForwarder;
    }

    @Override
    public void handlerAdded(final ChannelHandlerContext ctx) throws Exception {
        super.handlerAdded(ctx);
    }

    @Override
    protected ChannelFuture handshake(final ChannelHandlerContext ctx, final FullHttpRequest httpRequest, final WebSocketServerHandshaker handshaker, final ChannelPromise promise) throws Exception {
        String protocol = httpRequest.headers().get(HttpHeaderNames.SEC_WEBSOCKET_PROTOCOL);
        protocol = null == protocol ? PROTOCOL_WS_TUNNEL_REQUEST : protocol;
        if (PROTOCOL_AGENT_REGISTER.equals(protocol)) {
            ctx.pipeline().addLast(new ChannelInboundHandlerAdapter() {
                @Override
                public void userEventTriggered(final ChannelHandlerContext ctx, final Object evt) throws Exception {
                    if (evt instanceof WebSocketServerProtocolHandler.HandshakeComplete) {
                        webSocketBackhaulTunnelServerEngine.agentRegistered(((WebSocketServerProtocolHandler.HandshakeComplete) evt), ctx);
                    }
                    super.userEventTriggered(ctx, evt);
                }
            });
            return handshaker.handshake(ctx.channel(), httpRequest, EmptyHttpHeaders.INSTANCE, promise);
        }
        if (PROTOCOL_MGR_CONSOLE.equals(protocol)) {
            ctx.pipeline().addLast(new WebSocketBackhaulTunnelServerConsoleHandler(webSocketBackhaulTunnelServerEngine, webSocketBackhaulTunnelServerForwarder));
            return handshaker.handshake(ctx.channel(), httpRequest, EmptyHttpHeaders.INSTANCE, promise);
        }
        if (PROTOCOL_TUNNEL_BACKHAUL.equals(protocol)) {
            final Map<String, List<String>> params = parseParams(httpRequest.uri());
            String id = Util.last(params, "id");
            webSocketBackhaulTunnelServerEngine.tunnelResponded(id, ctx);
            return handshaker.handshake(ctx.channel(), httpRequest, EmptyHttpHeaders.INSTANCE, promise);
        }
        if (PROTOCOL_WS_TUNNEL_REQUEST.equals(protocol)) {
            wsTunnelRequested(httpRequest, ctx).addListener(new FutureListener<ChannelHandlerContext>() {

                @Override
                public void operationComplete(final Future<ChannelHandlerContext> future) throws Exception {
                    if (future.isSuccess()) {
                        handshaker.handshake(ctx.channel(), httpRequest, EmptyHttpHeaders.INSTANCE, promise);
                    } else {
                        promise.setFailure(future.cause());
                    }
                }
            });
            return promise;
        }
        if (PROTOCOL_TCP_TUNNEL_REQUEST.equals(protocol)) {

        }
        return handshaker.handshake(ctx.channel(), httpRequest, EmptyHttpHeaders.INSTANCE, promise);
    }

    /**
     * tcp over websocket or websocket frame through.
     */
    private Promise<ChannelHandlerContext> wsTunnelRequested(final FullHttpRequest handshake, final ChannelHandlerContext accessCtx) {
        final Promise<ChannelHandlerContext> backhaulPromise = accessCtx.executor().newPromise();
        final Map<String, List<String>> params = parseParams(handshake.uri());
        final String target = getTarget(params);
        final String agent = getAgent(params);
        if (null == target || target.isEmpty()) {
            return backhaulPromise.setFailure(new IllegalArgumentException("missing target"));
        }
        if (null == agent || agent.isEmpty()) {
            return backhaulPromise.setFailure(new IllegalArgumentException("missing agent"));
        }

        /*-
         * v1.0
         * tcp://hostname:port:      ws-client --ws--> server --tcp over ws--> agent --> tcp target
         * ws://hostname:port/path:  ws-client --ws--> server -------ws------> agent --> ws target
         */
        final String targetToUse = target.contains("://") ? target : "tcp://" + target;
        final URI uri = URI.create(targetToUse);
        final String id = accessCtx.channel().id().toString();
        return webSocketBackhaulTunnelServerEngine.tunnelRequested(id, agent, uri, accessCtx, backhaulPromise).addListener(new FutureListener<ChannelHandlerContext>() {
            @Override
            public void operationComplete(final Future<ChannelHandlerContext> backhaulFuture) throws Exception {
                if (backhaulFuture.isSuccess()) {
                    log.info("Connection established: {}:{}", uri.getHost(), uri.getPort());
                    final ChannelHandlerContext backhaulCtx = backhaulFuture.getNow();
                    backhaulCtx.channel().config().setAutoRead(false);

//                    accessCtx.pipeline().replace(accessCtx.name(), null, new WebSocketInboundRedirectHandler(backhaulCtx));
//                    backhaulCtx.pipeline().replace(backhaulCtx.name(), null, new WebSocketInboundRedirectHandler(accessCtx));
                    accessCtx.pipeline().replace("WS403Responder", null, new WebSocketInboundRedirectHandler(backhaulCtx));
                    backhaulCtx.pipeline().replace("WS403Responder", null, new WebSocketInboundRedirectHandler(accessCtx));

                    accessCtx.channel().config().setAutoRead(true);
                    backhaulCtx.channel().config().setAutoRead(true);
                } else {
                    accessCtx.writeAndFlush(new CloseWebSocketFrame(WebSocketCloseStatus.ENDPOINT_UNAVAILABLE, backhaulFuture.cause().getMessage()));
                }
            }
        });
    }

    private Map<String, List<String>> parseParams(final String uri) {
        return new QueryStringDecoder(uri).parameters();
    }

    private String getTarget(final Map<String, List<String>> params) {
        return Util.last(params, "target");
    }

    private String getAgent(final Map<String, List<String>> params) {
        return Util.last(params, "agent");
    }

}

package com.github.pangolin.server;

import com.github.pangolin.handler.TcpOverWebSocketDecodeHandler;
import com.github.pangolin.handler.TcpOverWebSocketEncodeHandler;
import com.github.pangolin.handler.WebSocketInboundRedirectHandler;
import com.github.pangolin.util.Util;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.http.QueryStringDecoder;
import io.netty.handler.codec.http.websocketx.CloseWebSocketFrame;
import io.netty.handler.codec.http.websocketx.Utf8FrameValidator;
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolHandler;
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolHandler.HandshakeComplete;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.FutureListener;
import io.netty.util.concurrent.Promise;
import lombok.extern.slf4j.Slf4j;

import java.net.URI;
import java.util.List;
import java.util.Map;

/**
 *
 */
@Slf4j
public class WebSocketBackhaulTunnelServerInitializer extends ChannelInboundHandlerAdapter {
    private static final String PROTOCOL_AGENT_REGISTER = "PASSIVE-REG";
    private static final String PROTOCOL_WS_TUNNEL_REQUEST = "";
    private static final String PROTOCOL_TCP_TUNNEL_REQUEST = "CONNECT";
    private static final String PROTOCOL_TUNNEL_BACKHAUL = "PASSIVE";
    private static final String PROTOCOL_MGR_CONSOLE = "CONSOLE";

    private final WebSocketBackhaulTunnelEngine webSocketBackhaulTunnelEngine;
    private final WebSocketBackhaulTunnelForwarder webSocketBackhaulTunnelForwarder;

    public WebSocketBackhaulTunnelServerInitializer(final WebSocketBackhaulTunnelEngine webSocketBackhaulTunnelEngine, final WebSocketBackhaulTunnelForwarder webSocketBackhaulTunnelForwarder) {
        this.webSocketBackhaulTunnelEngine = webSocketBackhaulTunnelEngine;
        this.webSocketBackhaulTunnelForwarder = webSocketBackhaulTunnelForwarder;
    }

    @Override
    public void userEventTriggered(final ChannelHandlerContext ctx, final Object evt) throws Exception {
        if (evt instanceof HandshakeComplete) {
            final HandshakeComplete handshake = (HandshakeComplete) evt;
            String subprotocol = handshake.selectedSubprotocol();
            subprotocol = null != subprotocol ? subprotocol : PROTOCOL_WS_TUNNEL_REQUEST;

            if (PROTOCOL_AGENT_REGISTER.equals(subprotocol)) {
                webSocketBackhaulTunnelEngine.agentRegistered(handshake, ctx);
            } else if (PROTOCOL_TUNNEL_BACKHAUL.equals(subprotocol)) {
                webSocketBackhaulTunnelEngine.tunnelResponded(handshake, ctx);
            } else if (PROTOCOL_WS_TUNNEL_REQUEST.equals(subprotocol)) {
                wsTunnelRequested(handshake, ctx);
            } else if (PROTOCOL_TCP_TUNNEL_REQUEST.equals(subprotocol)) {
                tcpTunnelRequested(handshake, ctx);
            } else if (PROTOCOL_MGR_CONSOLE.equals(subprotocol)) {
                ctx.pipeline().replace(ctx.name(), null, new WebSocketBackhaulTunnelConsoleHandler(webSocketBackhaulTunnelEngine, webSocketBackhaulTunnelForwarder));
            } else {
                ctx.writeAndFlush(new CloseWebSocketFrame(1002, "PROTOCOL_ERROR")).addListener(ChannelFutureListener.CLOSE);
            }
        }
        ctx.fireUserEventTriggered(evt);
    }

    @Override
    public void exceptionCaught(final ChannelHandlerContext ctx, final Throwable cause) throws Exception {
        log.error("Connection abort: {}", cause.getMessage(), cause);
        ctx.writeAndFlush(new CloseWebSocketFrame(1011, cause.getMessage())).addListener(ChannelFutureListener.CLOSE);
    }

    /**
     * tcp over websocket or websocket frame through.
     */
    private Promise<ChannelHandlerContext> wsTunnelRequested(final HandshakeComplete handshake, final ChannelHandlerContext accessCtx) {
        final Map<String, List<String>> params = parseParams(handshake.requestUri());
        final String target = getTarget(params);
        final String agentKey = getAgentKey(params);

        /*-
         * tcp://hostname:port:      ws-client --ws--> server --tcp over ws--> agent --> tcp target
         * ws://hostname:port/path:  ws-client --ws--> server -------ws------> agent --> ws target
         */
        final String targetToUse = target.contains("://") ? target : "tcp://" + target;
        final URI uri = URI.create(targetToUse);
        final String id = accessCtx.channel().id().toString();
        return webSocketBackhaulTunnelEngine.tunnelRequested(id, agentKey, uri, accessCtx).addListener(new FutureListener<ChannelHandlerContext>() {
            @Override
            public void operationComplete(final Future<ChannelHandlerContext> backhaulFuture) throws Exception {
                if (backhaulFuture.isSuccess()) {
                    log.info("Connection established: {}:{}", uri.getHost(), uri.getPort());
                    final ChannelHandlerContext backhaulCtx = backhaulFuture.getNow();
                    backhaulCtx.channel().config().setAutoRead(false);

                    accessCtx.pipeline().replace(accessCtx.name(), null, new WebSocketInboundRedirectHandler(backhaulCtx));
                    backhaulCtx.pipeline().replace(backhaulCtx.name(), null, new WebSocketInboundRedirectHandler(accessCtx));

                    accessCtx.channel().config().setAutoRead(true);
                    backhaulCtx.channel().config().setAutoRead(true);
                } else {
                    accessCtx.writeAndFlush(new CloseWebSocketFrame(1001, backhaulFuture.cause().getMessage()));
                }
            }
        });
    }

    /**
     * tcp through or websocket frame data by tcp.
     */
    private Promise<ChannelHandlerContext> tcpTunnelRequested(final HandshakeComplete handshake, final ChannelHandlerContext accessCtx) {
        final Map<String, List<String>> params = parseParams(handshake.requestUri());
        final String target = getTarget(params);
        final String agentKey = getAgentKey(params);

        /*-
         * tcp:tcp://hostname:port:      client --tcp--> server -----tcp over ws--------> agent --> tcp target
         * tcp:ws://hostname:port/path:  client --tcp--> server --websocket data frame--> agent --> ws target
         */
        final URI uri = URI.create(target);
        final String id = accessCtx.channel().id().toString();
        return webSocketBackhaulTunnelEngine.tunnelRequested(id, agentKey, uri, accessCtx).addListener(new FutureListener<ChannelHandlerContext>() {
            @Override
            public void operationComplete(final Future<ChannelHandlerContext> backhaulFuture) throws Exception {
                if (backhaulFuture.isSuccess()) {
                    final ChannelHandlerContext backhaulCtx = backhaulFuture.getNow();
                    backhaulCtx.channel().config().setAutoRead(false);

                    accessCtx.pipeline().replace(accessCtx.name(), null, new TcpOverWebSocketEncodeHandler(backhaulCtx));
                    backhaulCtx.pipeline().replace(backhaulCtx.name(), null, new TcpOverWebSocketDecodeHandler(accessCtx));

                    // remove websocket codec.
                    accessCtx.pipeline().remove("wsencoder");
                    accessCtx.pipeline().remove("wsdecoder");
                    accessCtx.pipeline().remove(Utf8FrameValidator.class);
                    accessCtx.pipeline().remove(WebSocketServerProtocolHandler.class);

                    accessCtx.channel().config().setAutoRead(true);
                    backhaulCtx.channel().config().setAutoRead(true);
                } else {
                    accessCtx.writeAndFlush(new CloseWebSocketFrame(1001, backhaulFuture.cause().getMessage()));
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

    private String getAgentKey(final Map<String, List<String>> params) {
        return Util.last(params, "agent");
    }

}

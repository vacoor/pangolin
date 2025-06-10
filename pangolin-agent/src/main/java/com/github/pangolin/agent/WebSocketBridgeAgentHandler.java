package com.github.pangolin.agent;

import com.github.pangolin.agent.util.Channels2;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.websocketx.*;
import io.netty.handler.timeout.IdleStateEvent;
import lombok.extern.slf4j.Slf4j;

import java.net.InetSocketAddress;
import java.net.URI;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

/**
 * WebSocket 回传通道代理.
 *
 * @see WebSocketBridgeAgentHandler2
 * @deprecated 1.2.2
 */
@Slf4j
@Deprecated
public class WebSocketBridgeAgentHandler extends SimpleChannelInboundHandler<WebSocketFrame> {
    private static final String AGENT_VERSION = "1.0";
    private static final String BACKHAUL_PROTOCOL = "PASSIVE";

    /**
     * tcp://...
     */
    private static final String TARGET_TYPE_TCP = "tcp";

    private enum State {SUSPENDED, INITIALIZING, INITIALIZED}

    /**
     * Agent name.
     */
    private final String name;

    /**
     * WebSocket agent handshaker.
     */
    private final WebSocketClientHandshaker handshaker;

    /**
     * WebSocket agent handshake http headers.
     */
    private final HttpHeaders customHttpHeaders;

    /**
     * WebSocket agent state.
     */
    private final AtomicReference<State> state = new AtomicReference<>(State.SUSPENDED);

    public WebSocketBridgeAgentHandler(final String name, final WebSocketClientHandshaker handshaker, final HttpHeaders customHttpHeaders) {
        this.name = name;
        this.handshaker = handshaker;
        this.customHttpHeaders = customHttpHeaders;
    }

    @Override
    public void channelActive(final ChannelHandlerContext ctx) throws Exception {
        if (null != customHttpHeaders) {
            final InetSocketAddress localAddress = (InetSocketAddress) ctx.channel().localAddress();
            customHttpHeaders.set("X-Node-Name", name);
            customHttpHeaders.set("X-Node-Version", AGENT_VERSION);
            customHttpHeaders.set("X-Node-Intranet", localAddress.getHostString());
        }
        super.channelActive(ctx);
    }

    @Override
    public void channelInactive(final ChannelHandlerContext ctx) throws Exception {
        state.set(State.SUSPENDED);
        super.channelInactive(ctx);
    }

    @Override
    public void userEventTriggered(final ChannelHandlerContext ctx, final Object evt) throws Exception {
        if (WebSocketClientProtocolHandler.ClientHandshakeStateEvent.HANDSHAKE_ISSUED.equals(evt)) {
            state.compareAndSet(State.SUSPENDED, State.INITIALIZING);
        } else if (WebSocketClientProtocolHandler.ClientHandshakeStateEvent.HANDSHAKE_COMPLETE.equals(evt)) {
            state.compareAndSet(State.INITIALIZING, State.INITIALIZED);
        } else if (evt instanceof IdleStateEvent) {
            if (ctx.channel().isActive() && State.INITIALIZED.equals(state.get())) {
                ctx.writeAndFlush(new PingWebSocketFrame());
            }
        } else {
            super.userEventTriggered(ctx, evt);
        }
    }

    @Override
    protected void channelRead0(final ChannelHandlerContext ctx, final WebSocketFrame frame) throws Exception {
        if (frame instanceof TextWebSocketFrame) {
            /*-
             * v1.0:
             * tcp:8080->tcp://172.16.0.12:7788
             * id->target_protocol://target_host:target_port
             */
            final String text = ((TextWebSocketFrame) frame).text();
            final String[] segments = text.split(Pattern.quote("->"), 2);
            final String id = segments[0];
            final URI target = URI.create(segments[1]);

            final WebSocketClientHandshaker backhaulHandshaker = newBackhaulHandshaker(id);
            if (TARGET_TYPE_TCP.equalsIgnoreCase(target.getScheme())) {
                final InetSocketAddress targetAddress = new InetSocketAddress(target.getHost(), target.getPort());
                Channels2.pipe(targetAddress, backhaulHandshaker, ctx.channel().eventLoop());
            }
        } else {
            ctx.fireChannelRead(frame.retain());
        }
    }

    private WebSocketClientHandshaker newBackhaulHandshaker(final String id) {
        final URI uri = handshaker.uri();
        final String endpoint = uri.getScheme() + "://" + uri.getHost() + ":" + uri.getPort() + uri.getPath();
        final URI backhaulWebSocketUri = URI.create(endpoint + "?id=" + id);
        return newHandshaker(backhaulWebSocketUri, BACKHAUL_PROTOCOL);
    }

    private WebSocketClientHandshaker newHandshaker(final URI webSocketEndpoint, final String subprotocol) {
        return WebSocketClientHandshakerFactory.newHandshaker(
                webSocketEndpoint, handshaker.version(), subprotocol,
                false, customHttpHeaders, handshaker.maxFramePayloadLength()
        );
    }

    @Override
    public void exceptionCaught(final ChannelHandlerContext webSocketContext, final Throwable cause) throws Exception {
        log.warn("Software caused connection abort: {}", cause.getMessage(), cause);
        webSocketContext.writeAndFlush(new CloseWebSocketFrame(WebSocketCloseStatus.INTERNAL_SERVER_ERROR, cause.getMessage())).addListener(ChannelFutureListener.CLOSE);
    }
}
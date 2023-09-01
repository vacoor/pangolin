package com.github.pangolin.proxy.backhaul.agent;

import com.github.pangolin.util.Channels2;
import com.github.pangolin.util.WebSocketUtils;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.websocketx.PingWebSocketFrame;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketClientHandshaker;
import io.netty.handler.codec.http.websocketx.WebSocketClientHandshakerFactory;
import io.netty.handler.codec.http.websocketx.WebSocketClientProtocolHandler;
import io.netty.handler.codec.http.websocketx.WebSocketFrame;
import io.netty.handler.timeout.IdleStateEvent;
import lombok.extern.slf4j.Slf4j;

import java.net.InetSocketAddress;
import java.net.URI;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

@Slf4j
public class WebSocketBackhaulProxyAgentHandler extends SimpleChannelInboundHandler<WebSocketFrame> {
    private static final String BACKHAUL_PROTOCOL = "TUNNEL_RESPONSE";

    private enum State {SUSPENDED, INITIALIZING, INITIALIZED}

    private final String name;
    private final WebSocketClientHandshaker handshaker;
    private final HttpHeaders customHttpHeaders;
    private final EventLoopGroup backhualGroup;
    private final AtomicReference<State> state = new AtomicReference<>(State.INITIALIZING);

    public WebSocketBackhaulProxyAgentHandler(final String name,
                                              final WebSocketClientHandshaker handshaker,
                                              final HttpHeaders customHttpHeaders, final EventLoopGroup backhaulGroup) {
        this.name = name;
        this.handshaker = handshaker;
        this.customHttpHeaders = customHttpHeaders;
        this.backhualGroup = backhaulGroup;
    }

    @Override
    public void channelActive(final ChannelHandlerContext ctx) throws Exception {
        if (null != customHttpHeaders) {
            final InetSocketAddress localAddress = (InetSocketAddress) ctx.channel().localAddress();
            customHttpHeaders.set("X-Node-Name", name);
            customHttpHeaders.set("X-Node-Version", "1.0");
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
             * v1.1:
             * id->ws:tcp://172.16.0.12:7788
             * id->ws:ws://172.16.0.12:7788
             * id->tcp:tcp://172.16.0.12:7788 --> since v1.1
             * id->tcp:ws://172.16.0.12:7788  --> since v1.1
             */
            final String text = ((TextWebSocketFrame) frame).text();
            final String[] segments = text.split(Pattern.quote("->"));
            final String id = segments[0];
            final URI target = URI.create(segments[1]);

            final WebSocketClientHandshaker backhaulHandshaker = newBackhaulHandshaker(id);
            if ("tcp".equalsIgnoreCase(target.getScheme())) {
                final InetSocketAddress socketAddress = new InetSocketAddress(target.getHost(), target.getPort());
                Channels2.pipe(socketAddress, backhaulHandshaker, backhualGroup);
            } else if ("ws".equalsIgnoreCase(target.getScheme()) || "wss".equalsIgnoreCase(target.getScheme())) {
                final WebSocketClientHandshaker upstreamHandshaker = newHandshaker(target, null);
                Channels2.pipe(upstreamHandshaker, backhaulHandshaker, backhualGroup);
            }
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
        WebSocketUtils.internalErrorClose(webSocketContext, cause.getMessage());
    }
}
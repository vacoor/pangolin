package com.github.pangolin.client;

import com.github.pangolin.agent.WebSocketBackhaulTunnelAgent;
import com.github.pangolin.util.Channels2;
import com.github.pangolin.util.WebSocketUtils;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.handler.codec.http.DefaultHttpHeaders;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.websocketx.*;
import io.netty.handler.codec.http.websocketx.WebSocketClientProtocolHandler.ClientHandshakeStateEvent;
import io.netty.handler.timeout.IdleStateEvent;
import io.netty.util.concurrent.DefaultThreadFactory;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.LockSupport;
import java.util.regex.Pattern;


/**
 * @see WebSocketBackhaulTunnelAgent
 * @deprecated {@link WebSocketBackhaulTunnelAgent}
 */
@Slf4j
@Deprecated
public class WebSocketTunnelClient {
    /**
     * 节点注册协议.
     */
    private static final String AGENT_REGISTER_PROTOCOL = "PASSIVE-REG";

    private enum ConnectionState {
        CONNECTED,
        SUSPENDED,
        RECONNECTED
    }

    private final EventLoopGroup workerGroup = new NioEventLoopGroup(2, new DefaultThreadFactory("WebSocket-Tunnel-Client", true));

    private final String name;
    private final URI serverEndpoint;

    private volatile Channel channel;
    private ConnectionState connectionState;

    private final AtomicBoolean started = new AtomicBoolean(false);

    /**
     * 重连延迟秒数.
     */
    private int reconnectDelaySeconds = 5;

    public WebSocketTunnelClient(final String name, final URI serverEndpoint) {
        this.name = name;
        this.serverEndpoint = serverEndpoint;
    }

    public URI getServerEndpoint() {
        return serverEndpoint;
    }

    public Channel start() throws IOException, InterruptedException {
        return channel = connect();
    }

    public boolean isRunning() {
        return channel.isActive();
    }

    public void shutdownGracefully() {
        if (null != channel) {
            channel.close();
        }
        workerGroup.shutdownGracefully();
    }

    private Channel connect() throws IOException, InterruptedException {
        final HttpHeaders customHttpHeaders = new DefaultHttpHeaders();
        return WebSocketUtils.openWebSocketChannel(serverEndpoint, AGENT_REGISTER_PROTOCOL, customHttpHeaders, createWebSocketTunnelClientHandler(customHttpHeaders));
    }

    private SimpleChannelInboundHandler<WebSocketFrame> createWebSocketTunnelClientHandler(final HttpHeaders customHttpHeaders) {
        return new SimpleChannelInboundHandler<WebSocketFrame>() {
            @Override
            public void channelActive(final ChannelHandlerContext ctx) throws Exception {
                final InetSocketAddress localAddress = (InetSocketAddress) ctx.channel().localAddress();
                customHttpHeaders.set("X-Node-Name", name);
                customHttpHeaders.set("X-Node-Version", "1.0");
                customHttpHeaders.set("X-Node-Intranet", localAddress.getHostString());
                super.channelActive(ctx);
            }

            @Override
            public void channelUnregistered(final ChannelHandlerContext ctx) throws Exception {
                connectionState = ConnectionState.SUSPENDED;
                ctx.channel().eventLoop().schedule(new Runnable() {
                    @Override
                    public void run() {
                        // log.debug("try to reconnect to tunnel server, uri: {}", "xx");
                        try {
                            channel = connect();
                        } catch (final Exception ex) {
                            // log.debug("reconnect fail: {}", ex.getMessage(), ex);
                        }
                    }
                }, reconnectDelaySeconds, TimeUnit.SECONDS);
            }

            @Override
            public void userEventTriggered(final ChannelHandlerContext webSocket, final Object evt) throws Exception {
                if (ClientHandshakeStateEvent.HANDSHAKE_ISSUED.equals(evt)) {
                    log.info("handshake issued");
                } else if (ClientHandshakeStateEvent.HANDSHAKE_COMPLETE.equals(evt)) {
                    log.info("handshake complete");
                    connectionState = ConnectionState.SUSPENDED.equals(connectionState) ? ConnectionState.RECONNECTED : ConnectionState.CONNECTED;
                } else if (evt instanceof IdleStateEvent) {
                    webSocket.writeAndFlush(new PingWebSocketFrame());
                } else {
                    super.userEventTriggered(webSocket, evt);
                }
            }

            @Override
            protected void channelRead0(final ChannelHandlerContext webSocketContext, final WebSocketFrame msg) throws Exception {
                onMessageReceived(webSocketContext, msg);
            }

            @Override
            public void exceptionCaught(final ChannelHandlerContext webSocketContext, final Throwable cause) throws Exception {
                log.warn("{} Software caused connection abort: {}", webSocketContext.channel(), cause.getMessage());
                WebSocketUtils.internalErrorClose(webSocketContext, cause.getMessage());
            }
        };
    }

    private void onMessageReceived(final ChannelHandlerContext webSocket, final WebSocketFrame frame) throws Exception {
        if (frame instanceof TextWebSocketFrame) {
            final TextWebSocketFrame textFrame = (TextWebSocketFrame) frame;
            /*-
             * tcp:8080->tcp://172.16.0.12:7788
             * id->target_protocol://target_host:target_port
             */
            final String text = textFrame.text();
            final String[] segments = text.split(Pattern.quote("->"));
            final String id = segments[0];
            final URI target = URI.create(segments[1]);
            final String endpoint = serverEndpoint.getScheme() + "://" + serverEndpoint.getHost() + ":" + serverEndpoint.getPort() + serverEndpoint.getPath();
            if ("tcp".equalsIgnoreCase(target.getScheme())) {
                final URI backhaulWebSocketUri = URI.create(endpoint + "?id=" + id);
                final WebSocketClientHandshaker backhaulHandshaker = newHandshaker(backhaulWebSocketUri, "PASSIVE", null);
                Channels2.pipe(new InetSocketAddress(target.getHost(), target.getPort()), backhaulHandshaker, workerGroup);
            } else if ("ws".equalsIgnoreCase(target.getScheme()) || "wss".equalsIgnoreCase(target.getScheme())) {
                final URI backhaulWebSocketUri = URI.create(endpoint + "?id=" + id);
                final WebSocketClientHandshaker backhaulHandshaker = newHandshaker(backhaulWebSocketUri, "PASSIVE", null);
                final WebSocketClientHandshaker upstreamHandshaker = newHandshaker(target, null, null);
                Channels2.pipe(upstreamHandshaker, backhaulHandshaker, workerGroup);
            }
        }
    }

    private WebSocketClientHandshaker newHandshaker(final URI webSocketEndpoint, final String subprotocol, final HttpHeaders customHttpHeaders) {
        return WebSocketClientHandshakerFactory.newHandshaker(webSocketEndpoint, WebSocketVersion.V13, subprotocol, true, customHttpHeaders);
    }

    public static void main(String[] args) throws Exception {
        final WebSocketTunnelClient client = new WebSocketTunnelClient("default", URI.create("ws://127.0.0.1:2345/tunnel"));
        client.start().closeFuture().await();
        System.out.println("Over");
        while (true) {
            LockSupport.park(TimeUnit.SECONDS.toNanos(10));
        }
    }
}
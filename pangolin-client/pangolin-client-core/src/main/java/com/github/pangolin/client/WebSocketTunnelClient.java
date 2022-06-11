package com.github.pangolin.client;

import com.github.pangolin.util.WebSocketForwarder;
import com.github.pangolin.util.WebSocketUtils;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.websocketx.PingWebSocketFrame;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketClientProtocolHandler.ClientHandshakeStateEvent;
import io.netty.handler.codec.http.websocketx.WebSocketFrame;
import io.netty.handler.timeout.IdleStateEvent;
import io.netty.handler.timeout.IdleStateHandler;
import io.netty.util.concurrent.DefaultThreadFactory;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.URI;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.LockSupport;
import java.util.regex.Pattern;


@Slf4j
public class WebSocketTunnelClient {
    /**
     * 节点注册协议.
     */
    private static final String NODE_REGISTER_PROTOCOL = "PASSIVE-REG";

    private enum ConnectionState {
        CONNECTED,
        SUSPENDED,
        RECONNECTED
    }

    private final EventLoopGroup workerGroup = new NioEventLoopGroup(2, new DefaultThreadFactory("WebSocket-Tunnel-Client", true));

    private final String name;
    private final URI serverEndpoint;

    private volatile ChannelFuture channelFuture;
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

    public ChannelFuture start() throws IOException, InterruptedException {
        return channelFuture = connect();
    }

    public boolean isRunning() {
        return channelFuture.channel().isActive();
    }

    public void shutdownGracefully() {
        if (null != channelFuture) {
            channelFuture.channel().close();
        }
        workerGroup.shutdownGracefully();
    }

    private ChannelFuture connect() throws IOException, InterruptedException {
        return connect(createWebSocketTunnelClientHandler());
    }

    private ChannelFuture connect(final ChannelHandler... handlers) throws IOException, InterruptedException {
        return WebSocketForwarder.openWebSocket(serverEndpoint, NODE_REGISTER_PROTOCOL, new ChannelInitializer<SocketChannel>() {
            @Override
            protected void initChannel(final SocketChannel ch) throws Exception {
                final ChannelPipeline cp = ch.pipeline();
                cp.addLast(new IdleStateHandler(0, 0, 50));
                cp.addLast(new WebSocketTunnelHandshakeHandler(name));
                cp.addLast(handlers);
            }
        });
    }

    private SimpleChannelInboundHandler<WebSocketFrame> createWebSocketTunnelClientHandler() {
        return new SimpleChannelInboundHandler<WebSocketFrame>() {

            @Override
            public void channelUnregistered(final ChannelHandlerContext ctx) throws Exception {
                connectionState = ConnectionState.SUSPENDED;
                ctx.channel().eventLoop().schedule(new Runnable() {
                    @Override
                    public void run() {
                        // log.debug("try to reconnect to tunnel server, uri: {}", "xx");
                        try {
                            channelFuture = connect();
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
                WebSocketForwarder.forwardToNativeSocket2(id, URI.create(endpoint + "?id=" + id), "PASSIVE", target);
            } else if ("ws".equalsIgnoreCase(target.getScheme()) || "wss".equalsIgnoreCase(target.getScheme())) {
                WebSocketForwarder.forwardToWebSocket2(id, URI.create(endpoint + "?id=" + id), "PASSIVE", target, null);
            }
        }
    }

    class WebSocketTunnelHandshakeHandler extends ChannelOutboundHandlerAdapter {
        private final String name;

        private WebSocketTunnelHandshakeHandler(final String name) {
            this.name = name;
        }

        @Override
        public void write(final ChannelHandlerContext ctx, final Object msg, final ChannelPromise promise) throws Exception {
            if (msg instanceof HttpRequest) {
                final HttpHeaders httpHeaders = ((HttpRequest) msg).headers();
                if (httpHeaders.contains(HttpHeaderNames.UPGRADE, HttpHeaderValues.WEBSOCKET, true)) {
                    final SocketAddress socketAddress = ctx.channel().localAddress();
                    String addressToUse = socketAddress.toString();
                    if (socketAddress instanceof InetSocketAddress) {
                        final InetSocketAddress address = (InetSocketAddress) socketAddress;
                        addressToUse = address.getAddress().getHostAddress();
                    }
                    httpHeaders.set("X-Node-Name", name);
                    httpHeaders.set("X-Node-Version", "1.0");
                    httpHeaders.set("X-Node-Intranet", addressToUse);

                    promise.addListener(new ChannelFutureListener() {
                        @Override
                        public void operationComplete(ChannelFuture future) {
                            ctx.pipeline().remove(ctx.name());
                        }
                    });
                }
            }
            super.write(ctx, msg, promise);
        }

    }

    public static void main(String[] args) throws Exception {
        // final WebSocketTunnelClient client = new WebSocketTunnelClient("default", URI.create("ws://10.45.90.148:2345/tunnel"));
        final WebSocketTunnelClient client = new WebSocketTunnelClient("default", URI.create("ws://127.0.0.1:2345/tunnel"));
        client.start().channel().closeFuture().await();
        System.out.println("Over");
        while (true) {
            LockSupport.park(TimeUnit.SECONDS.toNanos(10));
        }
    }
}
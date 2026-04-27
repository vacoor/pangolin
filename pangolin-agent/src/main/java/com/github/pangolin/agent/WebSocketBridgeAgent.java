package com.github.pangolin.agent;

import com.github.pangolin.agent.util.Channels2;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.handler.codec.http.DefaultHttpHeaders;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.websocketx.WebSocketClientHandshaker;
import io.netty.handler.codec.http.websocketx.WebSocketClientHandshakerFactory;
import io.netty.handler.codec.http.websocketx.WebSocketVersion;
import io.netty.handler.timeout.IdleStateHandler;
import io.netty.util.concurrent.Future;

import java.io.IOException;
import java.net.URI;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.LockSupport;


public class WebSocketBridgeAgent {

    private static final String PROTO_AGENT_SERVICE = "SERVICE";

    private final String name;
    private final URI webSocketServerEndpoint;
    private final int reconnectIntervalSeconds;

    private final EventLoopGroup workerGroup = new NioEventLoopGroup();
    private final AtomicBoolean started = new AtomicBoolean(false);

    private volatile ChannelFuture channelFuture;
    private volatile ScheduledFuture<?> reconnectFuture;

    public WebSocketBridgeAgent(final String name, final URI endpoint) {
        this(name, endpoint, 10);
    }

    public WebSocketBridgeAgent(final String name, final URI endpoint, final int reconnectIntervalSeconds) {
        this.name = name;
        this.webSocketServerEndpoint = endpoint;
        this.reconnectIntervalSeconds = reconnectIntervalSeconds;
    }

    public URI getWebSocketServerEndpoint() {
        return webSocketServerEndpoint;
    }

    public synchronized WebSocketBridgeAgent start() throws IOException, InterruptedException {
        if (!started.compareAndSet(false, true)) {
            throw new IllegalStateException("already started");
        }
        connect();
        return this;
    }

    private void connect() throws IOException, InterruptedException {
        channelFuture = connect0().addListener(new ChannelFutureListener() {
            @Override
            public void operationComplete(ChannelFuture future) throws Exception {
                if (!channelFuture.isSuccess()) {
                    reconnectIfNecessary();
                } else {
                    future.channel().closeFuture().addListener(new ChannelFutureListener() {
                        @Override
                        public void operationComplete(ChannelFuture closeFuture) throws Exception {
                            reconnectIfNecessary();
                        }
                    });
                }
            }
        });
    }

    private ChannelFuture connect0() throws IOException, InterruptedException {
        final HttpHeaders httpHeaders = new DefaultHttpHeaders();
        final WebSocketClientHandshaker handshaker = WebSocketClientHandshakerFactory.newHandshaker(
                webSocketServerEndpoint, WebSocketVersion.V13, PROTO_AGENT_SERVICE, true, httpHeaders
        );
        return Channels2.openWs(
                handshaker, workerGroup,
                new IdleStateHandler(60, 60, 60),
                new WebSocketBridgeAgentHandler(name, handshaker, httpHeaders)
        );
    }

    private void reconnectIfNecessary() {
        if (!started.get() || (null != reconnectFuture && !reconnectFuture.isDone())) {
            return;
        }
        reconnectFuture = workerGroup.next().schedule(new Runnable() {
            @Override
            public void run() {
                try {
                    connect();
                } catch (final Exception e) {
                    reconnectIfNecessary();
                }
            }
        }, reconnectIntervalSeconds, java.util.concurrent.TimeUnit.SECONDS);
    }

    public Future<?> shutdownGracefully() {
        if (started.compareAndSet(true, false)) {
            if (null != reconnectFuture) {
                reconnectFuture.cancel(false);
            }
            if (null != channelFuture && channelFuture.channel().isOpen()) {
                channelFuture.channel().close();
            }
        }
        return workerGroup.shutdownGracefully();
    }

    public static void main(String[] args) throws IOException, InterruptedException {
        final WebSocketBridgeAgent agent = new WebSocketBridgeAgent("Local", URI.create("ws://localhost:2345/tunnel/123"));
        agent.start();
        LockSupport.park();
    }
}
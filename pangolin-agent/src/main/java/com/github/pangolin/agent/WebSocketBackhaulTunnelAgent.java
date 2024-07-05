package com.github.pangolin.agent;

import com.github.pangolin.util.Channels2;
import io.netty.channel.ChannelFuture;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.handler.codec.http.DefaultHttpHeaders;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.websocketx.WebSocketClientHandshaker;
import io.netty.handler.codec.http.websocketx.WebSocketClientHandshakerFactory;
import io.netty.handler.codec.http.websocketx.WebSocketVersion;
import io.netty.handler.timeout.IdleStateHandler;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.GenericFutureListener;

import java.io.IOException;
import java.net.URI;
import java.util.concurrent.atomic.AtomicBoolean;


public class WebSocketBackhaulTunnelAgent {
    private static final String AGENT_REGISTER_PROTOCOL = "PASSIVE-REG";

    private final String name;
    private final URI webSocketServerEndpoint;

    private final EventLoopGroup workerGroup = new NioEventLoopGroup();
    private final AtomicBoolean started = new AtomicBoolean(false);

    private volatile ChannelFuture channelFuture;

    public WebSocketBackhaulTunnelAgent(final String name, final URI endpoint) {
        this.name = name;
        this.webSocketServerEndpoint = endpoint;
    }

    public URI getWebSocketServerEndpoint() {
        return webSocketServerEndpoint;
    }

    public ChannelFuture start() throws IOException, InterruptedException {
        if (started.compareAndSet(false, true)) {
            channelFuture = connect();
            return channelFuture;
        }
        return channelFuture;
    }

    private ChannelFuture connect() throws IOException, InterruptedException {
        final HttpHeaders customHttpHeaders = new DefaultHttpHeaders();
        final WebSocketClientHandshaker handshaker = WebSocketClientHandshakerFactory.newHandshaker(
                webSocketServerEndpoint, WebSocketVersion.V13, AGENT_REGISTER_PROTOCOL, true, customHttpHeaders
        );
        return Channels2.openWs(
                handshaker, workerGroup,
                new IdleStateHandler(600, 600, 600),
                new WebSocketBackhaulTunnelAgentHandler(name, handshaker, customHttpHeaders)
        );
    }

    public boolean isRunning() {
        if (null == channelFuture) {
            return false;
        }
        return !channelFuture.isDone() || channelFuture.channel().isActive();
    }

    public Future<?> shutdownGracefully() {
        if (null != channelFuture) {
            if (channelFuture.isDone() || !channelFuture.cancel(true)) {
                return channelFuture.channel().closeFuture().addListener(new GenericFutureListener<Future<? super Void>>() {
                    @Override
                    public void operationComplete(final Future<? super Void> future) {
                        workerGroup.shutdownGracefully();
                    }
                });
            }
        }
        return workerGroup.shutdownGracefully();
    }

}
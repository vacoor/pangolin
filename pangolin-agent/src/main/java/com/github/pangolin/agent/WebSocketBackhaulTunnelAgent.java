package com.github.pangolin.agent;

import com.github.pangolin.util.Channels2;
import io.netty.channel.Channel;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.handler.codec.http.DefaultHttpHeaders;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.websocketx.WebSocketClientHandshaker;
import io.netty.handler.codec.http.websocketx.WebSocketClientHandshakerFactory;
import io.netty.handler.codec.http.websocketx.WebSocketVersion;
import io.netty.handler.timeout.IdleStateHandler;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.net.URI;
import java.util.concurrent.atomic.AtomicBoolean;


@Slf4j
public class WebSocketBackhaulTunnelAgent {
    private static final String AGENT_REGISTER_PROTOCOL = "PASSIVE-REG";

    private final String name;
    private final URI webSocketServerEndpoint;
    private final EventLoopGroup workerGroup = new NioEventLoopGroup();
    private final AtomicBoolean started = new AtomicBoolean(false);

    private volatile Channel channel;

    public WebSocketBackhaulTunnelAgent(final String name, final URI endpoint) {
        this.name = name;
        this.webSocketServerEndpoint = endpoint;
    }

    public URI getWebSocketServerEndpoint() {
        return webSocketServerEndpoint;
    }

    public Channel start() throws IOException, InterruptedException {
        if (started.compareAndSet(false, true)) {
            channel = connect();
            return channel;
        }
        return channel;
    }

    private Channel connect() throws IOException, InterruptedException {
        final HttpHeaders customHttpHeaders = new DefaultHttpHeaders();
        final WebSocketClientHandshaker handshaker = WebSocketClientHandshakerFactory.newHandshaker(
                webSocketServerEndpoint, WebSocketVersion.V13, AGENT_REGISTER_PROTOCOL, true, customHttpHeaders
        );
        return Channels2.openWs(
                handshaker, workerGroup,
                new IdleStateHandler(600, 600, 600),
                new WebSocketBackhaulTunnelAgentHandler(name, handshaker, customHttpHeaders)
        ).sync().channel();
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

}
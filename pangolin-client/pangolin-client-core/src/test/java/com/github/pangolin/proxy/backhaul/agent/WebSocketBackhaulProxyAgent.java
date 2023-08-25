package com.github.pangolin.proxy.backhaul.agent;

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
import io.netty.util.concurrent.DefaultThreadFactory;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.net.URI;
import java.util.concurrent.atomic.AtomicBoolean;


@Slf4j
public class WebSocketBackhaulProxyAgent {
    /**
     * 节点注册协议.
     */
    private static final String AGENT_REGISTER_PROTOCOL = "AGENT-REGISTER";


    private final EventLoopGroup workerGroup = new NioEventLoopGroup(2, new DefaultThreadFactory("WebSocket-Tunnel-Client", true));

    private final String name;
    private final URI webSocketServerEndpoint;

    private volatile Channel channel;

    private final AtomicBoolean started = new AtomicBoolean(false);

    public WebSocketBackhaulProxyAgent(final String name, final URI endpoint) {
        this.name = name;
        this.webSocketServerEndpoint = endpoint;
    }

    public URI getWebSocketServerEndpoint() {
        return webSocketServerEndpoint;
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
        final WebSocketClientHandshaker handshaker = WebSocketClientHandshakerFactory.newHandshaker(
                webSocketServerEndpoint, WebSocketVersion.V13, AGENT_REGISTER_PROTOCOL, true, customHttpHeaders
        );
        return Channels2.openWs(
                handshaker, workerGroup,
                new IdleStateHandler(600, 600, 600),
                new WebSocketBackhaulProxyAgentHandler(name, handshaker, customHttpHeaders, workerGroup)
        ).sync().channel();
    }

    public static void main(String[] args) throws Exception {
        // final WebSocketTunnelClient client = new WebSocketTunnelClient("default", URI.create("ws://10.45.90.148:2345/tunnel"));
        final WebSocketBackhaulProxyAgent client = new WebSocketBackhaulProxyAgent("default", URI.create("ws://127.0.0.1:2345/tunnel"));
        client.start().closeFuture().await();
        System.out.println("Over");
    }
}
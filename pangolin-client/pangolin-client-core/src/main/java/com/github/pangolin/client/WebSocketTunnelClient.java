package com.github.pangolin.client;

import com.github.pangolin.util.WebSocketForwarder;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.ChannelPromise;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.http.DefaultHttpHeaders;
import io.netty.handler.codec.http.HttpClientCodec;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.websocketx.PingWebSocketFrame;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketClientHandshaker;
import io.netty.handler.codec.http.websocketx.WebSocketClientHandshakerFactory;
import io.netty.handler.codec.http.websocketx.WebSocketClientProtocolHandler;
import io.netty.handler.codec.http.websocketx.WebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketVersion;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.timeout.IdleStateEvent;
import io.netty.handler.timeout.IdleStateHandler;
import io.netty.util.concurrent.DefaultThreadFactory;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.URI;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import java.util.regex.Pattern;


@Slf4j
public class WebSocketTunnelClient {
    /**
     * 最大 HTTP 内容长度.
     */
    private static final int MAX_HTTP_CONTENT_LENGTH = 1024 * 1024 * 8;

    /**
     * 节点注册协议.
     */
    private static final String NODE_REGISTER_PROTOCOL = "PASSIVE-REG";

    private final EventLoopGroup workerGroup = new NioEventLoopGroup(2, new DefaultThreadFactory("WebSocket-Tunnel-Client", true));

    private final String name;
    private final URI tunnelServerEndpoint;
    private ChannelFuture channelFuture;

    /**
     * 重连延迟秒数.
     */
    private int reconnectDelaySeconds = 5;

    public WebSocketTunnelClient(final String name, final URI tunnelServerEndpoint) {
        this.name = name;
        this.tunnelServerEndpoint = getRegisterUri(tunnelServerEndpoint, name);
    }

    public URI getTunnelServerEndpoint() {
        return tunnelServerEndpoint;
    }

    private URI getRegisterUri(final URI uri, final String tunnelName) {
        return URI.create(uri.getScheme() + "://" + uri.getHost() + ":" + uri.getPort() + uri.getPath() + "?id=" + tunnelName);
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

    private ChannelFuture connect() throws IOException {
        final boolean isSecure = "wss".equalsIgnoreCase(tunnelServerEndpoint.getScheme());
        final int portToUse = 0 < tunnelServerEndpoint.getPort() ? tunnelServerEndpoint.getPort() : (isSecure ? 443 : 80);
        final String hostnameToUse = null != tunnelServerEndpoint.getHost() ? tunnelServerEndpoint.getHost() : "127.0.0.1";
        final SslContext sslContext = isSecure ? WebSocketForwarder.createSslContext() : null;
        final WebSocketClientHandshaker webSocketHandshaker = WebSocketClientHandshakerFactory.newHandshaker(
                tunnelServerEndpoint, WebSocketVersion.V13, NODE_REGISTER_PROTOCOL, true, new DefaultHttpHeaders()
        );

        final Bootstrap b = new Bootstrap();
        b.option(ChannelOption.TCP_NODELAY, true).option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 5000);
        b.group(workerGroup).channel(NioSocketChannel.class).remoteAddress(hostnameToUse, portToUse).handler(new ChannelInitializer<SocketChannel>() {
            @Override
            protected void initChannel(final SocketChannel ch) throws Exception {
                final ChannelPipeline cp = ch.pipeline();
                if (null != sslContext) {
                    cp.addLast(sslContext.newHandler(ch.alloc()));
                }
                cp.addLast(
                        new HttpClientCodec(),
                        new HttpObjectAggregator(MAX_HTTP_CONTENT_LENGTH),
                        new WebSocketClientProtocolHandler(webSocketHandshaker),
                        new IdleStateHandler(0, 0, 50)
                );

                /*-
                 * 添加请求头.
                 */
                cp.addLast(new ChannelOutboundHandlerAdapter() {
                    @Override
                    public void write(final ChannelHandlerContext ctx, final Object msg, final ChannelPromise promise) throws Exception {
                        if (msg instanceof HttpRequest) {
                            final HttpRequest httpRequest = (HttpRequest) msg;
                            final SocketAddress socketAddress = ctx.channel().localAddress();
                            String addressToUse = socketAddress.toString();
                            if (socketAddress instanceof InetSocketAddress) {
                                final InetSocketAddress address = (InetSocketAddress) socketAddress;
                                addressToUse = address.getAddress().getHostAddress();
                            }
                            httpRequest.headers().set("X-Node-Name", name);
                            httpRequest.headers().set("X-Node-Version", "1.0");
                            httpRequest.headers().set("X-Node-Intranet", addressToUse);
                        }
                        super.write(ctx, msg, promise);
                    }
                });
                cp.addLast(new SimpleChannelInboundHandler<WebSocketFrame>() {

                    @Override
                    protected void channelRead0(final ChannelHandlerContext webSocketContext, final WebSocketFrame msg) throws Exception {
                        onMessageReceived(webSocketContext, msg);
                    }

                    @Override
                    public void channelUnregistered(final ChannelHandlerContext ctx) throws Exception {
                        // super.channelUnregistered(ctx);
                        ctx.channel().eventLoop().schedule(new Runnable() {
                            @Override
                            public void run() {
                                log.error("try to reconnect to tunnel server, uri: {}", "xx");
                                try {
                                    connect();
                                } catch (final Exception ex) {
                                    log.error("reconnect fail: {}", ex.getMessage(), ex);
                                }
                            }
                        }, reconnectDelaySeconds, TimeUnit.SECONDS);
                    }

                    @Override
                    public void userEventTriggered(final ChannelHandlerContext webSocketContext, final Object evt) throws Exception {
                        if (evt instanceof IdleStateEvent) {
                            webSocketContext.writeAndFlush(new PingWebSocketFrame());
                        } else {
                            super.userEventTriggered(webSocketContext, evt);
                        }
                    }

                    @Override
                    public void exceptionCaught(final ChannelHandlerContext webSocketContext, final Throwable cause) throws Exception {
                        log.warn("{} Software caused connection abort: {}", webSocketContext.channel(), cause.getMessage());
                        webSocketContext.close();
                    }
                });
            }
        });
        return b.connect();
    }

    private void onMessageReceived(final ChannelHandlerContext webSocketContext, final WebSocketFrame frame) {
        if (frame instanceof TextWebSocketFrame) {
            final TextWebSocketFrame textFrame = (TextWebSocketFrame) frame;
            /*-
             * tcp:8080->tcp://172.16.0.12:7788
             * source_protocol:source_port->target_protocol://target_host:target_port
             */
            final String text = textFrame.text();
            final String[] segments = text.split(Pattern.quote("->"));
            final String requestId = segments[0];
            final URI target = URI.create(segments[1]);
            try {
                final String endpoint = tunnelServerEndpoint.getScheme() + "://" + tunnelServerEndpoint.getHost() + ":" + tunnelServerEndpoint.getPort() + tunnelServerEndpoint.getPath();
                if ("tcp".equalsIgnoreCase(target.getScheme())) {
                    WebSocketForwarder.forwardToNativeSocket(URI.create(endpoint + "?id=" + requestId), "PASSIVE", target, requestId);
                } else if ("ws".equalsIgnoreCase(target.getScheme()) || "wss".equalsIgnoreCase(target.getScheme())) {
                    WebSocketForwarder.forwardToWebSocket(URI.create(endpoint + "?id=" + requestId), "PASSIVE", target, null);
                }
            } catch (final Exception ex) {
                log.error("", ex);
                // webSocketContext.writeAndFlush(new CloseWebSocketFrame(WebSocketCloseStatus.ENDPOINT_UNAVAILABLE, ex.getMessage())).addListener(ChannelFutureListener.CLOSE);
            }
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
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
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketClientHandshaker;
import io.netty.handler.codec.http.websocketx.WebSocketClientHandshakerFactory;
import io.netty.handler.codec.http.websocketx.WebSocketClientProtocolHandler;
import io.netty.handler.codec.http.websocketx.WebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketVersion;
import io.netty.handler.ssl.SslContext;
import io.netty.util.concurrent.DefaultThreadFactory;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.URI;
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

    private final String tunnelName;
    private final URI tunnelServerEndpoint;
    private ChannelFuture channelFuture;

    public WebSocketTunnelClient(final String tunnelName, final URI tunnelServerEndpoint) {
        this.tunnelName = tunnelName;
        this.tunnelServerEndpoint = getRegisterUri(tunnelServerEndpoint, tunnelName);
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

    private ChannelFuture connect() throws IOException, InterruptedException {
        final boolean isSecure = "wss".equalsIgnoreCase(tunnelServerEndpoint.getScheme());
        final int portToUse = 0 < tunnelServerEndpoint.getPort() ? tunnelServerEndpoint.getPort() : (isSecure ? 443 : 80);
        final String hostnameToUse = null != tunnelServerEndpoint.getHost() ? tunnelServerEndpoint.getHost() : "127.0.0.1";
        final SslContext sslContext = isSecure ? WebSocketForwarder.createSslContext() : null;
        final WebSocketClientHandshaker webSocketHandshaker = WebSocketClientHandshakerFactory.newHandshaker(
                tunnelServerEndpoint, WebSocketVersion.V13, NODE_REGISTER_PROTOCOL, true, createCustomHttpHeaders()
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
                cp.addLast(new HttpClientCodec());
                cp.addLast(new HttpObjectAggregator(MAX_HTTP_CONTENT_LENGTH));
                cp.addLast(new WebSocketClientProtocolHandler(webSocketHandshaker));
                cp.addLast(new ChannelOutboundHandlerAdapter() {
                    @Override
                    public void write(final ChannelHandlerContext ctx, final Object msg, final ChannelPromise promise) throws Exception {
                        if (msg instanceof HttpRequest) {
                            final HttpRequest httpRequest = (HttpRequest) msg;
                            final SocketAddress socketAddress = ctx.channel().localAddress();
                            String addressToUse = socketAddress.toString();
                            if (socketAddress instanceof InetSocketAddress) {
                                addressToUse = ((InetSocketAddress) socketAddress).getAddress().getHostAddress();
                            }
                            httpRequest.headers().set("x-node-address", addressToUse);
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
                    public void exceptionCaught(final ChannelHandlerContext webSocketContext, final Throwable cause) throws Exception {
                        log.warn("{} Software caused connection abort: {}", webSocketContext.channel(), cause.getMessage());
                        webSocketContext.close();
                    }
                });
            }
        });
        return b.connect();
    }

    private HttpHeaders createCustomHttpHeaders() throws IOException {
        final DefaultHttpHeaders headers = new DefaultHttpHeaders();
        headers.set("x-node-name", tunnelName);
        headers.set("x-node-version", "1.0");
        return headers;
    }

    private void onMessageReceived(final ChannelHandlerContext webSocketContext, final WebSocketFrame message) {
        if (message instanceof TextWebSocketFrame) {
            final TextWebSocketFrame frame = (TextWebSocketFrame) message;
            /*-
             * tcp:123->tcp://127.0.0.1
             */
            final String text = frame.text();
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
        client.start().sync().channel().closeFuture().await();
    }
}
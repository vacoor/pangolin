package com.github.tube.client;

import com.github.tube.util.WebSocketForwarder;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.http.DefaultHttpHeaders;
import io.netty.handler.codec.http.HttpClientCodec;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpObjectAggregator;
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
import java.net.Socket;
import java.net.URI;
import java.util.regex.Pattern;


@Slf4j
public class WebSocketTunnelClient {
    private static final int MAX_HTTP_CONTENT_LENGTH = Integer.MAX_VALUE;

    private static String determineLocalAddress(final URI endpoint) throws IOException {
        Socket socket = null;
        try {
            socket = new Socket(endpoint.getHost(), endpoint.getPort());
            return socket.getLocalAddress().getHostAddress();
        } finally {
            if (null != socket) {
                socket.close();
            }
        }
    }

    public static void main(String[] args) throws InterruptedException, IOException {
        final String uri = "ws://127.0.0.1:2345/tunnel";
        final URI endpoint = URI.create(uri + "?id=default");
        final String localAddress = determineLocalAddress(endpoint);

        // System.out.println(determineLocalAddress(URI.create("ssh://139.196.88.115:22")));

        final boolean isSecure = "wss".equalsIgnoreCase(endpoint.getScheme());
        final SslContext context = null;

        final DefaultHttpHeaders headers = new DefaultHttpHeaders();
        headers.set("X-TUNNEL-ID", "TEST");
        headers.set("X-TUNNEL-NAME", "TEST");
        headers.set("X-TUNNEL-VERSION", "1.0");
        headers.set("X-TUNNEL-ADDRESS", localAddress);

        final EventLoopGroup group = new NioEventLoopGroup(1, new DefaultThreadFactory("WebSocket-PIPE-MASTER", true));
        final WebSocketClientHandshaker masterWebSocketHandshaker = WebSocketClientHandshakerFactory.newHandshaker(
                endpoint, WebSocketVersion.V13, "PASSIVE-REG", true, headers
        );

        ChannelFuture closeFuture = null;
        try {
            final Bootstrap b = new Bootstrap();
            b.option(ChannelOption.TCP_NODELAY, true);
            b.option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 5000);

            b.group(group).channel(NioSocketChannel.class).handler(new ChannelInitializer<SocketChannel>() {
                @Override
                protected void initChannel(final SocketChannel ch) throws Exception {
                    final ChannelPipeline cp = ch.pipeline();
                    if (null != context) {
                        cp.addLast(context.newHandler(ch.alloc()));
                    }
                    cp.addLast(new HttpClientCodec());
                    cp.addLast(new HttpObjectAggregator(MAX_HTTP_CONTENT_LENGTH));
                    cp.addLast(new WebSocketClientProtocolHandler(masterWebSocketHandshaker));
                    cp.addLast(new SimpleChannelInboundHandler<WebSocketFrame>() {

                        @Override
                        protected void channelRead0(final ChannelHandlerContext webSocketContext, final WebSocketFrame msg) throws Exception {
                            if (msg instanceof TextWebSocketFrame) {
                                final TextWebSocketFrame frame = (TextWebSocketFrame) msg;
                                /*-
                                 * tcp:123->tcp://127.0.0.1
                                 */
                                final String text = frame.text();
                                final String[] segments = text.split(Pattern.quote("->"));
                                final String requestId = segments[0];
                                final URI target = URI.create(segments[1]);
                                try {
                                    if ("tcp".equalsIgnoreCase(target.getScheme())) {
                                        final HttpHeaders headers = new DefaultHttpHeaders();
                                        headers.set("X-REQUEST-ID", requestId);
                                        WebSocketForwarder.forwardToNativeSocket(URI.create(uri + "?id=" + requestId), "PASSIVE", headers, target, requestId);
                                    } else if ("ws".equalsIgnoreCase(target.getScheme()) || "wss".equalsIgnoreCase(target.getScheme())) {
                                        WebSocketForwarder.forwardToWebSocket(URI.create(uri + "?id=" + requestId), "PASSIVE", target, null);
                                    }
                                } catch (final Exception ex) {
                                    log.error("", ex);
                                    // webSocketContext.writeAndFlush(new CloseWebSocketFrame(WebSocketCloseStatus.ENDPOINT_UNAVAILABLE, ex.getMessage())).addListener(ChannelFutureListener.CLOSE);
                                }
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
            closeFuture = b.connect(endpoint.getHost(), endpoint.getPort()).sync().channel().closeFuture();
        } finally {
            if (null != closeFuture) {
                closeFuture.addListener(new ChannelFutureListener() {
                    @Override
                    public void operationComplete(final ChannelFuture future) throws Exception {
                        group.shutdownGracefully();
                    }
                });
            } else {
                group.shutdownGracefully();
            }
        }
        closeFuture.await();
    }
}
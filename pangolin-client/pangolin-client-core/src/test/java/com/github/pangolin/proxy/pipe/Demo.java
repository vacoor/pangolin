package com.github.pangolin.proxy.pipe;

import com.github.pangolin.util.Channels;
import com.github.pangolin.util.Redirects;
import com.github.pangolin.util.WebSocketUtils;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandler;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.codec.http.DefaultHttpHeaders;
import io.netty.handler.codec.http.HttpClientCodec;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.websocketx.WebSocketClientHandshakerFactory;
import io.netty.handler.codec.http.websocketx.WebSocketClientProtocolHandler;
import io.netty.handler.codec.http.websocketx.WebSocketVersion;
import io.netty.handler.codec.http.websocketx.extensions.compression.WebSocketClientCompressionHandler;
import io.netty.handler.timeout.IdleStateHandler;
import io.netty.util.concurrent.DefaultThreadFactory;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.GenericFutureListener;
import io.netty.util.concurrent.GlobalEventExecutor;
import io.netty.util.concurrent.Promise;

import javax.net.ssl.SSLException;
import java.net.URI;

import static com.github.pangolin.util.WebSocketForwarder.pipeWebSocket;

/**
 * TODO DOC ME!.
 *
 * @author changhe.yang
 * @since 20230822
 */
public class Demo {

    public static ChannelFuture forwardToWebSocket2(final String id,
                                                    final NioEventLoopGroup group,
                                                    final URI webSocketEndpoint1, final String webSocketProtocol1,
                                                    final URI webSocketEndpoint2, final String webSocketProtocol2) throws SSLException, InterruptedException {
        return openWebSocketChannel(group, webSocketEndpoint1, webSocketProtocol1, new ChannelInboundHandlerAdapter() {
            final Promise<ChannelHandlerContext> webSocketBackhaulLinkPromise = GlobalEventExecutor.INSTANCE.newPromise();

            @Override
            public void exceptionCaught(final ChannelHandlerContext ctx, final Throwable cause) throws Exception {
                super.exceptionCaught(ctx, cause);
            }

            @Override
            public void channelRead(final ChannelHandlerContext ctx, final Object msg) throws Exception {
                ChannelHandlerContext backhaul = webSocketBackhaulLinkPromise.sync().getNow();
                System.out.println("ws1: " + msg);
                Redirects.webSocketRedirectToWebSocket(backhaul).channelRead(ctx, msg);
            }

            @Override
            public void userEventTriggered(final ChannelHandlerContext webSocketContext1, final Object evt) throws Exception {
                if (WebSocketClientProtocolHandler.ClientHandshakeStateEvent.HANDSHAKE_COMPLETE.equals(evt)) {
//                    webSocketContext1.channel().config().setAutoRead(false);


                    openWebSocketChannel(group, webSocketEndpoint2, webSocketProtocol2, new ChannelInboundHandlerAdapter() {
                        ChannelInboundHandler codec = Redirects.webSocketRedirectToWebSocket(webSocketContext1);
                        @Override
                        public void channelRead(final ChannelHandlerContext ctx, final Object msg) throws Exception {
//                            super.channelRead(ctx, msg);
                            if (webSocketContext1.channel().isActive()) {
                                System.out.println("ws2: " + msg);
                                codec.channelRead(ctx, msg);
                            }
                        }

                        @Override
                        public void userEventTriggered(final ChannelHandlerContext webSocketContext2, final Object evt) throws Exception {
                            if (WebSocketClientProtocolHandler.ClientHandshakeStateEvent.HANDSHAKE_COMPLETE.equals(evt)) {
                                /*
                                System.out.println("OK2");
                                webSocketContext2.channel().config().setAutoRead(false);

                                webSocketContext2.pipeline().addAfter(webSocketContext2.name(), null, pipeWebSocket(webSocketContext1));
                                webSocketContext1.pipeline().addAfter(webSocketContext1.name(), null, pipeWebSocket(webSocketContext2));

                                webSocketContext2.pipeline().remove(webSocketContext2.name());
                                webSocketContext1.pipeline().remove(webSocketContext1.name());

                                webSocketContext2.channel().config().setAutoRead(true);
                                webSocketContext1.channel().config().setAutoRead(true);
                                */
                                webSocketBackhaulLinkPromise.setSuccess(webSocketContext2);
                            }
                        }
                        @Override
                        public void exceptionCaught(final ChannelHandlerContext ctx, final Throwable cause) throws Exception {
                            super.exceptionCaught(ctx, cause);
                        }
                    }).channel().closeFuture().addListener(new GenericFutureListener<Future<? super Void>>() {
                        @Override
                        public void operationComplete(final Future<? super Void> future) throws Exception {
                            if (webSocketContext1.channel().isActive()) {
                                WebSocketUtils.policyViolationClose(webSocketContext1, "Destination unavailable");
                            }
                        }
                    });
//                    webSocketBackhaulLinkPromise.sync();
//                    System.out.println("OK1");
                }
            }
        });
    }

    public static ChannelFuture openWebSocketChannel(final NioEventLoopGroup webSocketGroup, final URI webSocketEndpoint, final String webSocketProtocol, final ChannelHandler... webSocketHandlers) throws SSLException, InterruptedException {
        final boolean isSecure = "wss".equalsIgnoreCase(webSocketEndpoint.getScheme());
//        final SslContext context = isSecure ? createSslContext() : null;
        final int portToUse = 0 < webSocketEndpoint.getPort() ? webSocketEndpoint.getPort() : (isSecure ? 443 : 80);

//        final EventLoopGroup webSocketGroup = new NioEventLoopGroup(1, new DefaultThreadFactory("WebSocket-PIPE-MASTER", true));
        return Channels.open(webSocketEndpoint.getHost(), portToUse, webSocketGroup, new ChannelInitializer<SocketChannel>() {
            @Override
            protected void initChannel(final SocketChannel ch) {
                final ChannelPipeline cp = ch.pipeline();
//                if (null != context) {
//                    cp.addLast(context.newHandler(ch.alloc()));
//                }
                cp.addLast(new IdleStateHandler(0, 0, 50));
                cp.addLast(new HttpClientCodec(), new HttpObjectAggregator(1024 * 1024 * 8));
                cp.addLast(WebSocketClientCompressionHandler.INSTANCE);
                cp.addLast(new WebSocketClientProtocolHandler(WebSocketClientHandshakerFactory.newHandshaker(
                        webSocketEndpoint, WebSocketVersion.V13, webSocketProtocol, true, new DefaultHttpHeaders(), 65536, true, true
                ), false));
                cp.addLast(webSocketHandlers);
            }
        });
    }

    public static void main(String[] args) throws Exception {
        final NioEventLoopGroup g = new NioEventLoopGroup(2);
        final ChannelFuture future = forwardToWebSocket2("1", g,
                URI.create("ws://127.0.0.1:8899/ws/echo"), "",
                // URI.create("ws://127.0.0.1:2345/tunnel?id=WEBSOCKET-TEST"), "TUNNEL_RESPONSE",
                URI.create("ws://127.0.0.1:8899/ws/echo"), ""
        );
        future.sync().channel().closeFuture().addListener(new ChannelFutureListener() {
            @Override
            public void operationComplete(final ChannelFuture future) throws Exception {
                System.out.println();
            }
        }).await();

        g.shutdownGracefully();

        System.out.println("Wait over");
    }
}

package com.github.pangolin.proxy.pipe;

import com.github.pangolin.proxy.bridge.BackpressureHandler;
import com.github.pangolin.util.Channels;
import com.github.pangolin.util.WebSocketForwarder;
import com.github.pangolin.util.WebSocketUtils;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.handler.codec.http.websocketx.WebSocketClientProtocolHandler;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.GenericFutureListener;

import javax.net.ssl.SSLException;
import java.net.URI;

import static com.github.pangolin.util.WebSocketForwarder.openWebSocketChannel;
import static com.github.pangolin.util.WebSocketForwarder.pipeWebSocket;

/**
 * TODO DOC ME!.
 *
 * @author changhe.yang
 * @since 20230822
 */
public class Demo {

    public static Channel forwardToWebSocket2(final String id,
                                              final URI webSocketEndpoint1, final String webSocketProtocol1,
                                              final URI webSocketEndpoint2, final String webSocketProtocol2) throws SSLException, InterruptedException {
        return openWebSocketChannel(webSocketEndpoint1, webSocketProtocol1, new BackpressureHandler() {
            @Override
            public void userEventTriggered(final ChannelHandlerContext webSocketContext1, final Object evt) throws Exception {
                if (WebSocketClientProtocolHandler.ClientHandshakeStateEvent.HANDSHAKE_COMPLETE.equals(evt)) {
                    webSocketContext1.channel().config().setAutoRead(false);
                    this.pendingRead = this.pendingWrite = true;

                    openWebSocketChannel(webSocketEndpoint2, webSocketProtocol2, new BackpressureHandler() {
                        @Override
                        public void userEventTriggered(final ChannelHandlerContext webSocketContext2, final Object evt) throws Exception {
                            if (WebSocketClientProtocolHandler.ClientHandshakeStateEvent.HANDSHAKE_COMPLETE.equals(evt)) {
                                webSocketContext2.channel().config().setAutoRead(false);
                                this.pendingRead = this.pendingWrite = true;

                                webSocketContext2.pipeline().addAfter(webSocketContext2.name(), null, pipeWebSocket(webSocketContext1));
                                webSocketContext1.pipeline().addAfter(webSocketContext1.name(), null, pipeWebSocket(webSocketContext2));

                                webSocketContext2.pipeline().remove(webSocketContext2.name());
                                webSocketContext1.pipeline().remove(webSocketContext1.name());

                                webSocketContext2.channel().config().setAutoRead(true);
                                webSocketContext1.channel().config().setAutoRead(true);
                            }
                        }
                    }).closeFuture().addListener(new GenericFutureListener<Future<? super Void>>() {
                        @Override
                        public void operationComplete(final Future<? super Void> future) throws Exception {
                            if (webSocketContext1.channel().isActive()) {
                                WebSocketUtils.policyViolationClose(webSocketContext1, "Destination unavailable");
                            }
                        }
                    });
                }
            }
        });
    }

    public static void main(String[] args) throws Exception {
        Channels.open("183.47.114.18", 80, false, new NioEventLoopGroup(), new ChannelInboundHandlerAdapter() {
            @Override
            public void channelRegistered(final ChannelHandlerContext ctx) throws Exception {
                ctx.channel().config().setAutoRead(true);
            }
        }).sync().channel().closeFuture().await();
        /*
        final Channel future = WebSocketForwarder.forwardToWebSocket2("1",
                URI.create("ws://127.0.0.1:8899/ws/echo"), "",
                // URI.create("ws://127.0.0.1:2345/tunnel?id=WEBSOCKET-TEST"), "TUNNEL_RESPONSE",
                URI.create("ws://127.0.0.1:8899/ws/echo"), ""
        );
        future.closeFuture().await();
        System.out.println("Wait over");
        */
    }
}

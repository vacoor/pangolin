package com.github.pangolin.util;

import com.github.pangolin.handler.TcpInboundRedirectHandler;
import com.github.pangolin.handler.TcpOverWebSocketDecodeHandler;
import com.github.pangolin.handler.TcpOverWebSocketEncodeHandler;
import com.github.pangolin.handler.WebSocketInboundRedirectHandler;
import io.netty.channel.*;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.codec.http.HttpClientCodec;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.websocketx.WebSocketClientHandshaker;
import io.netty.handler.codec.http.websocketx.WebSocketClientProtocolHandler;
import io.netty.handler.flow.FlowControlHandler;
import io.netty.handler.ssl.SslContext;

import javax.net.ssl.SSLException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.URI;

/**
 *
 */
public class Channels2 {


    /**
     * client -> websocket server(downstream) <-- backhaul connection -- br --> destination socket(upstream)
     *
     * @deprecated 1.2.2
     */
    @Deprecated
    public static ChannelFuture pipe(final SocketAddress upstream, final WebSocketClientHandshaker downstream, final EventLoopGroup brGroup) throws InterruptedException {
        return Channels.open(upstream, false, brGroup, new ChannelInboundHandlerAdapter() {
            @Override
            public void channelActive(final ChannelHandlerContext upstreamCtx) throws Exception {
                openWs(downstream, brGroup, new ChannelInboundHandlerAdapter() {
                    @Override
                    public void handlerAdded(final ChannelHandlerContext downstreamCtx) throws Exception {
                        final ChannelPipeline cp = downstreamCtx.pipeline();
                        if (null == cp.get(FlowControlHandler.class)) {
                            final ChannelHandlerContext wsCtx = cp.context(WebSocketClientProtocolHandler.class);
                            cp.addBefore(wsCtx.name(), FlowControlHandler.class.getName(), new FlowControlHandler());
                        }
                    }

                    @Override
                    public void userEventTriggered(final ChannelHandlerContext downstreamCtx, final Object evt) throws Exception {
                        if (WebSocketClientProtocolHandler.ClientHandshakeStateEvent.HANDSHAKE_COMPLETE.equals(evt)) {
                            downstreamCtx.channel().config().setAutoRead(false);

                            upstreamCtx.pipeline().replace(upstreamCtx.name(), "upstream-br", new TcpOverWebSocketEncodeHandler(downstreamCtx));
                            downstreamCtx.pipeline().replace(downstreamCtx.name(), "downstream-br", new TcpOverWebSocketDecodeHandler(upstreamCtx));

                            upstreamCtx.channel().config().setAutoRead(true);
                            downstreamCtx.channel().config().setAutoRead(true);
                        }
                    }
                }).addListener(new ChannelFutureListener() {
                    @Override
                    public void operationComplete(final ChannelFuture future) throws Exception {
                        if (!future.isSuccess()) {
                            // can't open backhaul connection.
                            future.channel().close();
                            upstreamCtx.channel().close();
                        }
                    }
                });
            }
        }).addListener(ChannelFutureListener.CLOSE_ON_FAILURE);
    }

    public static ChannelFuture openWs(final WebSocketClientHandshaker handshaker,
                                       final EventLoopGroup group, final ChannelHandler... wsHandlers) throws InterruptedException, SSLException {
        final URI webSocketEndpoint = handshaker.uri();
        final InetSocketAddress remoteAddress = new InetSocketAddress(webSocketEndpoint.getHost(), webSocketEndpoint.getPort());
        final boolean isSecure = "wss".equalsIgnoreCase(webSocketEndpoint.getScheme());
        final SslContext sslContext = isSecure ? Channels.createClientSslContext() : null;

        return Channels.open(remoteAddress, null, true, group, new ChannelInitializer<SocketChannel>() {

            @Override
            protected void initChannel(final SocketChannel ch) throws Exception {
                final ChannelPipeline cp = ch.pipeline();
                if (null != sslContext) {
                    cp.addLast(sslContext.newHandler(ch.alloc()));
                }
                cp.addLast(new HttpClientCodec());
                cp.addLast(new HttpObjectAggregator(1024 * 1024 * 8));
                cp.addLast(new WebSocketClientProtocolHandler(handshaker));
                cp.addLast(wsHandlers);
            }

        });
    }
}

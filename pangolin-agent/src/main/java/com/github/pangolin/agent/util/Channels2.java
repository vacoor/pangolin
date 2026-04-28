package com.github.pangolin.agent.util;

import com.github.pangolin.util.Channels;
import io.netty.channel.*;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.codec.http.HttpClientCodec;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.websocketx.WebSocketClientHandshaker;
import io.netty.handler.codec.http.websocketx.WebSocketClientProtocolHandler;
import io.netty.handler.ssl.SslContext;

import javax.net.ssl.SSLException;
import java.net.InetSocketAddress;
import java.net.URI;

/**
 *
 */
public class Channels2 {


    public static ChannelFuture openWs(final WebSocketClientHandshaker handshaker,
                                       final EventLoopGroup group, final ChannelHandler... wsHandlers) throws InterruptedException, SSLException {
        final URI webSocketEndpoint = handshaker.uri();
        final String host = webSocketEndpoint.getHost();
        if (null == host || host.isEmpty()) {
            throw new IllegalArgumentException("WebSocket endpoint host is required: " + webSocketEndpoint);
        }
        final boolean isSecure = "wss".equalsIgnoreCase(webSocketEndpoint.getScheme());
        final InetSocketAddress remoteAddress = new InetSocketAddress(
                host, getPort(webSocketEndpoint)
        );
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

    private static int getPort(final URI webSocketEndpoint) {
        final String scheme = webSocketEndpoint.getScheme();
        if (!"ws".equalsIgnoreCase(scheme) && !"wss".equalsIgnoreCase(scheme)) {
            throw new IllegalArgumentException("Unsupported WebSocket scheme: " + scheme);
        }

        final int port = webSocketEndpoint.getPort();
        if (port >= 0) {
            return port;
        }
        if ("ws".equalsIgnoreCase(scheme)) {
            return 80;
        }
        if ("wss".equalsIgnoreCase(scheme)) {
            return 443;
        }
        throw new IllegalArgumentException("Unsupported WebSocket scheme: " + scheme);
    }
}

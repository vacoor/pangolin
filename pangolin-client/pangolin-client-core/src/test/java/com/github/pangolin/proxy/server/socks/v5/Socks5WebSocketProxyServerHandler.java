package com.github.pangolin.proxy.server.socks.v5;

import com.github.pangolin.util.Channels;
import com.github.pangolin.handler.SocketOverWebSocketDecodeHandler;
import com.github.pangolin.handler.SocketOverWebSocketEncodeHandler;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.codec.http.DefaultHttpHeaders;
import io.netty.handler.codec.http.HttpClientCodec;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.websocketx.WebSocketClientHandshakerFactory;
import io.netty.handler.codec.http.websocketx.WebSocketClientProtocolHandler;
import io.netty.handler.codec.http.websocketx.WebSocketVersion;
import io.netty.handler.codec.socksx.v5.*;
import io.netty.handler.ssl.SslContext;
import lombok.extern.slf4j.Slf4j;

import java.net.URI;

@Slf4j
public class Socks5WebSocketProxyServerHandler extends Socks5ProxyServerHandler {
    private final URI webSocketEndpoint;
    private final String webSocketProtocol;

    public Socks5WebSocketProxyServerHandler(final URI webSocketEndpoint, final String webSocketProtocol, final NioEventLoopGroup proxyWorkersGroup) {
        this(null, null, webSocketEndpoint, webSocketProtocol, proxyWorkersGroup);
    }

    public Socks5WebSocketProxyServerHandler(final String username, final String password, final URI webSocketEndpoint, final String webSocketProtocol, final NioEventLoopGroup proxyWorkersGroup) {
        super(username, password, proxyWorkersGroup);
        this.webSocketEndpoint = webSocketEndpoint;
        this.webSocketProtocol = webSocketProtocol;
    }

    @Override
    protected void connect(final ChannelHandlerContext requestCtx, final Socks5CommandRequest request, final EventLoopGroup proxyGroup) throws Exception {
        final int port = request.dstPort();
        final String address = request.dstAddr();
        final Socks5AddressType addressType = request.dstAddrType();

        requestCtx.channel().config().setAutoRead(false);

        final boolean isSecure = "wss".equalsIgnoreCase(webSocketEndpoint.getScheme());
        final SslContext context = isSecure ? Channels.createClientSslContext() : null;
        final HttpHeaders handshakeHeaders = newHandshakeHeaders(request, requestCtx);
        Channels.open(webSocketEndpoint.getHost(), webSocketEndpoint.getPort(), true, proxyGroup, new ChannelInitializer<SocketChannel>() {
            @Override
            protected void initChannel(final SocketChannel ch) throws Exception {
                final ChannelPipeline cp = ch.pipeline();
                if (null != context) {
                    cp.addLast(context.newHandler(ch.alloc()));
                }
                cp.addLast(new HttpClientCodec());
                cp.addLast(new HttpObjectAggregator(1024 * 1024 * 8));
                // cp.addLast(WebSocketClientCompressionHandler.INSTANCE);
                cp.addLast(new WebSocketClientProtocolHandler(WebSocketClientHandshakerFactory.newHandshaker(
                        webSocketEndpoint, WebSocketVersion.V13, webSocketProtocol, true, handshakeHeaders, 65536, true, true
                ), false));
                cp.addLast(new ChannelInboundHandlerAdapter() {
                    @Override
                    public void userEventTriggered(final ChannelHandlerContext delegateCtx, final Object evt) throws Exception {
                        if (WebSocketClientProtocolHandler.ClientHandshakeStateEvent.HANDSHAKE_COMPLETE.equals(evt)) {
                            delegateCtx.channel().config().setAutoRead(false);

                            requestCtx.pipeline().replace(requestCtx.handler(), null, new SocketOverWebSocketEncodeHandler(delegateCtx));
                            delegateCtx.pipeline().replace(this, null, new SocketOverWebSocketDecodeHandler(requestCtx));

                            requestCtx.writeAndFlush(new DefaultSocks5CommandResponse(Socks5CommandStatus.SUCCESS, addressType)).addListener(g -> requestCtx.pipeline().remove(Socks5ServerEncoder.DEFAULT));

                            requestCtx.channel().config().setAutoRead(true);
                            delegateCtx.channel().config().setAutoRead(true);
                        }
                    }
                });
            }
        }).addListener(future -> {
            if (future.isSuccess()) {
                log.info("Connection to {}:{}: Connected", address, port);
            } else {
                log.warn("Failed to Connect to {}:{}: {}", address, port, future.cause());
                requestCtx.writeAndFlush(new DefaultSocks5CommandResponse(Socks5CommandStatus.HOST_UNREACHABLE, addressType)).addListener(ChannelFutureListener.CLOSE);
            }
        }).channel().closeFuture().addListener(f -> {
            if (requestCtx.channel().isActive()) {
                log.info("Connection to {}:{} closed", address, port);
                Channels.closeOnFlush(requestCtx.channel());
            }
        });
    }

    private HttpHeaders newHandshakeHeaders(final Socks5CommandRequest request, final ChannelHandlerContext ctx) {
        final DefaultHttpHeaders httpHeaders = new DefaultHttpHeaders();
        httpHeaders.set("X-TARGET-ADDRESS", request.dstAddr());
        httpHeaders.setInt("X-TARGET-PORT", request.dstPort());
        return httpHeaders;
    }
}

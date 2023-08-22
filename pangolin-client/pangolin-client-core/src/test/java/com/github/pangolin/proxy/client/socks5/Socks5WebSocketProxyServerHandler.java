package com.github.pangolin.proxy.client.socks5;

import com.github.pangolin.util.Channels;
import com.github.pangolin.util.Redirects;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.codec.http.DefaultHttpHeaders;
import io.netty.handler.codec.http.HttpClientCodec;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.websocketx.WebSocketClientHandshakerFactory;
import io.netty.handler.codec.http.websocketx.WebSocketClientProtocolHandler;
import io.netty.handler.codec.http.websocketx.WebSocketVersion;
import io.netty.handler.codec.socksx.v5.*;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import lombok.extern.slf4j.Slf4j;

import javax.net.ssl.SSLException;
import java.net.URI;

/**
 * TODO DOC ME!.
 *
 * @author changhe.yang
 * @since 20230821
 */
@Slf4j
public class Socks5WebSocketProxyServerHandler extends Socks5ProxyServerHandler {
    private final URI webSocketEndpoint;
    private final String webSocketProtocol;

    public Socks5WebSocketProxyServerHandler(final URI webSocketEndpoint, final String webSocketProtocol, final NioEventLoopGroup group) {
        this(null, null, webSocketEndpoint, webSocketProtocol, group);
    }

    public Socks5WebSocketProxyServerHandler(final String username, final String password, final URI webSocketEndpoint, final String webSocketProtocol, final NioEventLoopGroup group) {
        super(username, password, group);
        this.webSocketEndpoint = webSocketEndpoint;
        this.webSocketProtocol = webSocketProtocol;
    }

    @Override
    protected void connectToTarget(final NioEventLoopGroup group, final ChannelHandlerContext requestCtx, Socks5CommandRequest request) throws InterruptedException {
        final int port = request.dstPort();
        final String address = request.dstAddr();
        final Socks5AddressType addressType = request.dstAddrType();

        requestCtx.channel().config().setAutoRead(false);

        final boolean isSecure = "wss".equalsIgnoreCase(webSocketEndpoint.getScheme());
        final int portToUse = 0 < webSocketEndpoint.getPort() ? webSocketEndpoint.getPort() : (isSecure ? 443 : 80);

        final DefaultHttpHeaders httpHeaders = new DefaultHttpHeaders();
        httpHeaders.set("X-TARGET-ADDRESS", address);
        httpHeaders.setInt("X-TARGET-PORT", port);

        Channels.open(webSocketEndpoint.getHost(), portToUse, true, group, new ChannelInitializer<SocketChannel>() {
            @Override
            protected void initChannel(final SocketChannel ch) throws Exception {
                final ChannelPipeline cp = ch.pipeline();
                if (isSecure) {
                    cp.addLast(createClientSslContext().newHandler(ch.alloc()));
                }
//                cp.addLast(new IdleStateHandler(0, 0, 50));
                cp.addLast(new HttpClientCodec(), new HttpObjectAggregator(1024 * 1024 * 8));
//                cp.addLast(WebSocketClientCompressionHandler.INSTANCE);
                cp.addLast(new WebSocketClientProtocolHandler(WebSocketClientHandshakerFactory.newHandshaker(
                        webSocketEndpoint, WebSocketVersion.V13, webSocketProtocol, true, httpHeaders, 65536, true, true
                ), false));
                cp.addLast(new ChannelInboundHandlerAdapter() {
                    @Override
                    public void userEventTriggered(final ChannelHandlerContext delegateCtx, final Object evt) throws Exception {
                        if (WebSocketClientProtocolHandler.ClientHandshakeStateEvent.HANDSHAKE_COMPLETE.equals(evt)) {
                            delegateCtx.channel().config().setAutoRead(false);

                            requestCtx.pipeline().replace(requestCtx.handler(), null, Redirects.socketRedirectToWebSocket(delegateCtx));
                            delegateCtx.pipeline().replace(this, null, Redirects.webSocketRedirectToSocket(requestCtx));

                            requestCtx.writeAndFlush(new DefaultSocks5CommandResponse(Socks5CommandStatus.SUCCESS, addressType)).addListener(g -> requestCtx.pipeline().remove(Socks5ServerEncoder.DEFAULT));

                            requestCtx.channel().config().setAutoRead(true);
                            delegateCtx.channel().config().setAutoRead(true);
                        }
                    }
                });
            }
        }).addListener(f -> {
            if (f.isSuccess()) {
                log.debug("连接到目标地址({}/{}:{})成功", addressType, address, port);
            } else {
                log.warn("连接到目标地址({}/{}:{})失败: {}", addressType, address, port, f.cause());
                requestCtx.writeAndFlush(new DefaultSocks5CommandResponse(Socks5CommandStatus.HOST_UNREACHABLE, addressType)).addListener(ChannelFutureListener.CLOSE);
            }
        }).channel().closeFuture().addListener(f -> {
            if (requestCtx.channel().isActive()) {
                log.info("目标地址({}/{}:{})断开连接", addressType, address, port, f.cause());
                requestCtx.writeAndFlush(Unpooled.EMPTY_BUFFER).addListener(ChannelFutureListener.CLOSE);
            }
        });
    }

    private SslContext createClientSslContext() throws SSLException {
        return SslContextBuilder.forClient().trustManager(InsecureTrustManagerFactory.INSTANCE).build();
    }

}

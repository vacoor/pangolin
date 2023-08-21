package com.github.pangolin.proxy.bridge.socks5;

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
import io.netty.handler.codec.http.websocketx.extensions.compression.WebSocketClientCompressionHandler;
import io.netty.handler.codec.socksx.v5.*;
import io.netty.handler.timeout.IdleStateHandler;
import lombok.extern.slf4j.Slf4j;

import java.net.URI;

/**
 * TODO DOC ME!.
 *
 * @author changhe.yang
 * @since 20230821
 */
@Slf4j
public class Socks5ProxyServerHandler2 extends Socks5ProxyServerHandler {

    public Socks5ProxyServerHandler2(final NioEventLoopGroup group) {
        super(group, null, null);
    }

    public Socks5ProxyServerHandler2(final NioEventLoopGroup group, final String username, final String password) {
        super(group, username, password);
    }

    @Override
    protected void connectToTarget(final NioEventLoopGroup group, final ChannelHandlerContext requestCtx, Socks5CommandRequest request) throws InterruptedException {
        final int port = request.dstPort();
        final String address = request.dstAddr();
        final Socks5AddressType addressType = request.dstAddrType();

        requestCtx.channel().config().setAutoRead(false);

        final URI webSocketEndpoint = URI.create("ws://127.0.0.1:8899/ws/echo");
        final boolean isSecure = "wss".equalsIgnoreCase(webSocketEndpoint.getScheme());
        final int portToUse = 0 < webSocketEndpoint.getPort() ? webSocketEndpoint.getPort() : (isSecure ? 443 : 80);
        final DefaultHttpHeaders headers = new DefaultHttpHeaders();
        headers.set("X-TARGET-ADDRESS", address);
        headers.setInt("X-TARGET-PORT", port);

        Channels.open(webSocketEndpoint.getHost(), portToUse, true, group, new ChannelInitializer<SocketChannel>() {
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
                        webSocketEndpoint, WebSocketVersion.V13, "", true, headers, 65536, false, true
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

                /*
                cp.addLast(new WebSocketProxyClientHandler(webSocketEndpoint, "") {
                    @Override
                    public void userEventTriggered(final ChannelHandlerContext ctx, final Object evt) throws Exception {
                        if (WebSocketClientProtocolHandler.ClientHandshakeStateEvent.HANDSHAKE_COMPLETE.equals(evt)) {
                            System.out.println();
                            requestCtx.writeAndFlush(new DefaultSocks5CommandResponse(Socks5CommandStatus.SUCCESS, addressType)).addListener(g -> requestCtx.pipeline().remove(Socks5ServerEncoder.DEFAULT));
                        }
                        super.userEventTriggered(ctx, evt);
                    }
                });
                */
            }
        }).addListener(f -> {
            if (f.isSuccess()) {
                log.debug("连接到目标地址({}/{}:{})成功", addressType, address, port);
            } else {
                log.warn("连接到目标地址({}/{}:{})失败: {}", addressType, address, port, f.cause());
                requestCtx.writeAndFlush(new DefaultSocks5CommandResponse(Socks5CommandStatus.HOST_UNREACHABLE, addressType));
            }
        }).channel().closeFuture().addListener(f -> {
            if (requestCtx.channel().isActive()) {
                log.info("目标地址({}/{}:{})断开连接", addressType, address, port, f.cause());
                requestCtx.writeAndFlush(Unpooled.EMPTY_BUFFER).addListener(ChannelFutureListener.CLOSE);
            }
        });
    }

    private static boolean nullSafeEquals(Object a, Object b) {
        return (a == b) || (a != null && a.equals(b));
    }
}

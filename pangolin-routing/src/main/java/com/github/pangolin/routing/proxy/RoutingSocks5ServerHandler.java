package com.github.pangolin.routing.proxy;

import com.github.pangolin.handler.TcpInboundRedirectHandler;
import com.github.pangolin.routing.internal.server.socks.Socks5ProxyServerHandler;
import com.github.pangolin.util.Channels;
import io.netty.channel.*;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.codec.socksx.v5.Socks5CommandRequest;
import io.netty.resolver.NoopAddressResolverGroup;
import lombok.extern.slf4j.Slf4j;

import java.net.InetSocketAddress;
import java.net.SocketAddress;

/**
 *
 */
@Slf4j
public class RoutingSocks5ServerHandler extends Socks5ProxyServerHandler {
    private final ProxyServer proxy;

    public RoutingSocks5ServerHandler(final ProxyServer proxy) {
        this.proxy = proxy;
    }

    @Override
    protected ChannelFuture connect(final ChannelHandlerContext ctx, final Socks5CommandRequest request) throws Exception {
        ctx.channel().config().setAutoRead(false);

        final InetSocketAddress destinationAddress = new InetSocketAddress(request.dstAddr(), request.dstPort());
        final ChannelHandler networkHandler = this.select(destinationAddress);

        return Channels.open(destinationAddress, NoopAddressResolverGroup.INSTANCE, false, ctx.channel().eventLoop(), new ChannelInitializer<SocketChannel>() {
            @Override
            protected void initChannel(final SocketChannel ch) throws Exception {
                if (null != networkHandler) {
                    ch.pipeline().addFirst(networkHandler);
                }
                ch.pipeline().addLast(new ChannelInboundHandlerAdapter() {
                    @Override
                    public void channelRegistered(final ChannelHandlerContext delegateCtx) throws Exception {
                        delegateCtx.pipeline().replace(this, null, new TcpInboundRedirectHandler(ctx));
                        ctx.pipeline().replace(ctx.handler(), null, new TcpInboundRedirectHandler(delegateCtx));

                        delegateCtx.channel().config().setAutoRead(true);
                        ctx.channel().config().setAutoRead(true);
                    }
                });
            }
        });
    }

    private ChannelHandler select(final SocketAddress destinationAddress) {
        if (null == proxy || !(destinationAddress instanceof InetSocketAddress)) {
            return null;
        }
        final InetSocketAddress sa = (InetSocketAddress) destinationAddress;
        return proxy.newProxyHandler(sa);
    }
}

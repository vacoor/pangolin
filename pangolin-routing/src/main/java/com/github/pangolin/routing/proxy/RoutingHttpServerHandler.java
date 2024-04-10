package com.github.pangolin.routing.proxy;

import com.github.pangolin.handler.TcpInboundRedirectHandler;
import com.github.pangolin.routing.internal.server.http.HttpProxyServerHandler;
import com.github.pangolin.util.Channels;
import io.netty.channel.*;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.resolver.NoopAddressResolverGroup;

import java.net.InetSocketAddress;
import java.net.SocketAddress;

public class RoutingHttpServerHandler extends HttpProxyServerHandler {
    private final ProxyServer proxy;

    public RoutingHttpServerHandler(final ProxyServer proxy) {
        this.proxy = proxy;
    }

    @Override
    protected ChannelFuture connect(final ChannelHandlerContext ctx, final HttpRequest httpRequest) throws Exception {
        ctx.channel().config().setAutoRead(false);

        final InetSocketAddress targetAddress = getHttpRequestAddress(httpRequest);
        final String address = targetAddress.getHostString();
        final int port = targetAddress.getPort();

        final InetSocketAddress destinationAddress = new InetSocketAddress(address, port);
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
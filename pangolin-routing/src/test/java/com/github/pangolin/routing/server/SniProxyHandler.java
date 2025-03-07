package com.github.pangolin.routing.server;

import com.github.pangolin.handler.TcpInboundRedirectHandler;
import com.github.pangolin.routing.support.SocketChannelFactory;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandler;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.ssl.AbstractSniHandler;
import io.netty.util.concurrent.Future;

import java.net.InetSocketAddress;
import java.util.List;

/**
 * @since 20240618
 */
public class SniProxyHandler extends AbstractSniHandler<Void> {
    private final SocketChannelFactory factory;

    public SniProxyHandler(final SocketChannelFactory factory) {
        this.factory = factory;
    }

    volatile ByteBuf copy;

    @Override
    protected void decode(final ChannelHandlerContext ctx, final ByteBuf in, final List<Object> out) throws Exception {
        copy = in.copy();
        super.decode(ctx, in, out);
    }

    @Override
    protected Future<Void> lookup(final ChannelHandlerContext ctx, final String hostname) throws Exception {
        ChannelInboundHandler me = this;
        final InetSocketAddress sa = (InetSocketAddress) ctx.channel().localAddress();
        return factory.open(new InetSocketAddress(hostname, sa.getPort()), ctx.channel().config().getConnectTimeoutMillis(), true, ctx.channel().eventLoop(), new ChannelInboundHandlerAdapter() {
            @Override
            public void channelActive(final ChannelHandlerContext delegateCtx) throws Exception {
                ctx.pipeline().addLast(new TcpInboundRedirectHandler(delegateCtx));
                delegateCtx.pipeline().replace(this, null, new TcpInboundRedirectHandler(ctx));
            }
        });
    }

    @Override
    protected void onLookupComplete(final ChannelHandlerContext ctx, final String hostname, final Future<Void> future) throws Exception {
        ctx.pipeline().remove(this);
        ((ChannelFuture) future).channel().writeAndFlush(copy);
    }

}

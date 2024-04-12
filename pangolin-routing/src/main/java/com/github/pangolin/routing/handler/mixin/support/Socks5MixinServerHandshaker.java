package com.github.pangolin.routing.handler.mixin.support;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPipeline;
import io.netty.handler.codec.socksx.SocksVersion;
import io.netty.handler.codec.socksx.v5.Socks5InitialRequestDecoder;
import io.netty.handler.codec.socksx.v5.Socks5ServerEncoder;

public class Socks5MixinServerHandshaker extends SocksMixinServerHandshaker {
    private final ChannelHandler[] handlers;

    public Socks5MixinServerHandshaker(final ChannelHandler... handlers) {
        super(SocksVersion.SOCKS5);
        this.handlers = handlers;
    }

    @Override
    protected void doHandshake(final ChannelHandlerContext ctx, final ByteBuf in) {
        final ChannelPipeline cp = ctx.pipeline();
        cp.addAfter(ctx.name(), null, Socks5ServerEncoder.DEFAULT);
        cp.addAfter(ctx.name(), null, new Socks5InitialRequestDecoder());
        cp.addLast(handlers);
    }

    public final Socks5MixinServerHandshaker of(final ChannelHandler... handlers) {
        return new Socks5MixinServerHandshaker(handlers);
    }
}
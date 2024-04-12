package com.github.pangolin.routing.handler.mixin.support;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPipeline;
import io.netty.handler.codec.socksx.SocksVersion;
import io.netty.handler.codec.socksx.v4.Socks4ServerDecoder;
import io.netty.handler.codec.socksx.v4.Socks4ServerEncoder;

public class Socks4MixinServerHandshaker extends SocksMixinServerHandshaker {
    private final ChannelHandler[] handlers;

    public Socks4MixinServerHandshaker(final ChannelHandler... handlers) {
        super(SocksVersion.SOCKS4a);
        this.handlers = handlers;
    }

    @Override
    protected void doHandshake(final ChannelHandlerContext ctx, final ByteBuf in) {
        final ChannelPipeline cp = ctx.pipeline();
        cp.addAfter(ctx.name(), null, Socks4ServerEncoder.INSTANCE);
        cp.addAfter(ctx.name(), null, new Socks4ServerDecoder());
        cp.addLast(handlers);
    }

    public final Socks4MixinServerHandshaker of(final ChannelHandler... handlers) {
        return new Socks4MixinServerHandshaker(handlers);
    }
}
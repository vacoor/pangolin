package com.github.pangolin.routing.handshake;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPipeline;
import io.netty.handler.codec.socksx.SocksVersion;
import io.netty.handler.codec.socksx.v5.Socks5InitialRequestDecoder;
import io.netty.handler.codec.socksx.v5.Socks5ServerEncoder;

public class Socks5ServerHandshaker extends SocksServerHandshaker {
    private final ChannelHandler[] handlers;

    public Socks5ServerHandshaker(final ChannelHandler... handlers) {
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

}
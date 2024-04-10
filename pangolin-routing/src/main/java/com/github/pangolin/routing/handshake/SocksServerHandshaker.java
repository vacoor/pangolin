package com.github.pangolin.routing.handshake;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.socksx.SocksVersion;

public abstract class SocksServerHandshaker implements ServerHandshaker {
    private final SocksVersion version;

    public SocksServerHandshaker(final SocksVersion version) {
        this.version = version;
    }

    @Override
    public boolean handshake(final ChannelHandlerContext ctx, final ByteBuf in) {
        final byte b = in.getByte(in.readerIndex());
        final SocksVersion v = SocksVersion.valueOf(b);
        if (version.equals(v)) {
            this.doHandshake(ctx, in);
            return true;
        }
        return false;
    }

    protected abstract void doHandshake(final ChannelHandlerContext ctx, final ByteBuf in);

}
package com.github.pangolin.routing.server.mixin.support;

import com.github.pangolin.routing.server.mixin.MixinServerHandshaker;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.socksx.SocksVersion;

public abstract class SocksMixinServerHandshaker implements MixinServerHandshaker {
    private final SocksVersion version;

    public SocksMixinServerHandshaker(final SocksVersion version) {
        this.version = version;
    }

    /**
     * {@inheritDoc}
     */
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
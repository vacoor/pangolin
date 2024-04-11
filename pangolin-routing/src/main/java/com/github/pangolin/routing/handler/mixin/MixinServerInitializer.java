package com.github.pangolin.routing.handler.mixin;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPipeline;
import io.netty.handler.codec.ByteToMessageDecoder;

import java.util.List;

public class MixinServerInitializer extends ByteToMessageDecoder {
    private final MixinServerHandshaker[] handshakers;

    public MixinServerInitializer(final MixinServerHandshaker... handshakers) {
        this.handshakers = handshakers;
    }

    @Override
    protected void decode(final ChannelHandlerContext ctx, final ByteBuf in, final List<Object> out) throws Exception {
        if (in.writerIndex() == in.readerIndex()) {
            return;
        }

        final ChannelPipeline cp = ctx.pipeline();
        if (this.handshake(ctx, in)) {
            cp.remove(this);
        } else {
            in.skipBytes(in.readableBytes());
            ctx.close();
        }
    }

    private boolean handshake(final ChannelHandlerContext ctx, final ByteBuf in) {
        for (final MixinServerHandshaker handshaker : handshakers) {
            if (handshaker.handshake(ctx, in)) {
                return true;
            }
        }
        return false;
    }

}
package com.github.pangolin.routing.server.mixin.support;

import com.github.pangolin.routing.server.mixin.MixinServerHandshaker;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPipeline;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpServerCodec;

public class HttpMixinServerHandshaker implements MixinServerHandshaker {
    /**
     * HTTP handlers.
     */
    private final ChannelHandler[] handlers;

    private HttpMixinServerHandshaker(final ChannelHandler... handlers) {
        this.handlers = handlers;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean handshake(final ChannelHandlerContext ctx, final ByteBuf in) {
        final int readerIndex = in.readerIndex();
        if (in.writerIndex() == readerIndex || in.readableBytes() < 2) {
            return false;
        }

        final int magic1 = in.getUnsignedByte(readerIndex);
        final int magic2 = in.getUnsignedByte(readerIndex + 1);
        if (isHttp(magic1, magic2)) {
            this.doHandshake(ctx, in);
            return true;
        }
        return false;
    }

    protected void doHandshake(final ChannelHandlerContext ctx, final ByteBuf in) {
        final ChannelPipeline cp = ctx.pipeline();
        cp.addAfter(ctx.name(), null, new HttpObjectAggregator(8 * 1024 * 1024));
        cp.addAfter(ctx.name(), null, new HttpServerCodec());
        cp.addLast(handlers);
    }

    private boolean isHttp(final int magic1, final int magic2) {
        return magic1 == 'G' && magic2 == 'E' || // GET
                magic1 == 'P' && magic2 == 'O' || // POST
                magic1 == 'P' && magic2 == 'U' || // PUT
                magic1 == 'H' && magic2 == 'E' || // HEAD
                magic1 == 'O' && magic2 == 'P' || // OPTIONS
                magic1 == 'P' && magic2 == 'A' || // PATCH
                magic1 == 'D' && magic2 == 'E' || // DELETE
                magic1 == 'T' && magic2 == 'R' || // TRACE
                magic1 == 'C' && magic2 == 'O';   // CONNECT
    }

    public static HttpMixinServerHandshaker of(final ChannelHandler... handlers) {
        return new HttpMixinServerHandshaker(handlers);
    }
}
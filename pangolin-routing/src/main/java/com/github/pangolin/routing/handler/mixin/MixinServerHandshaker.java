package com.github.pangolin.routing.handler.mixin;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;

public interface MixinServerHandshaker {

    /**
     * Handshake for protocol.
     *
     * @param ctx context
     * @param in  read buffer
     * @return true if handshake success, otherwise false
     */
    boolean handshake(final ChannelHandlerContext ctx, final ByteBuf in);

}
package com.github.pangolin.routing.handler.mixin;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;

public interface MixinServerHandshaker {

    boolean handshake(final ChannelHandlerContext ctx, final ByteBuf in);

}
package com.github.pangolin.routing.handshake;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;

public interface ServerHandshaker {

    boolean handshake(final ChannelHandlerContext ctx, final ByteBuf in);

}
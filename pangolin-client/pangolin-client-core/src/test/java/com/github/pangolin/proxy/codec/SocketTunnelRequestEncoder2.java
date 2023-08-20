package com.github.pangolin.proxy.codec;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToByteEncoder;

public class SocketTunnelRequestEncoder2 extends MessageToByteEncoder<WebSocketTunnelRequest> {

    @Override
    protected void encode(final ChannelHandlerContext ctx, final WebSocketTunnelRequest request, final ByteBuf out) throws Exception {
        out.writeByte(request.getVersion());
        out.writeByte(request.getCommand());
    }

}
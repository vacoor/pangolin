package com.github.pangolin.proxy.codec;

import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToMessageDecoder;
import io.netty.handler.codec.http.websocketx.BinaryWebSocketFrame;

import java.util.List;

public class WebSocketTunnelRequestDecoder extends MessageToMessageDecoder<BinaryWebSocketFrame> {

    @Override
    protected void decode(final ChannelHandlerContext ctx, final BinaryWebSocketFrame msg, final List<Object> out) throws Exception {
        /*
        final ByteBuf buf = msg.content();
        final byte version = buf.readByte();
        final byte cmd = buf.readByte();
        buf.skipBytes(1);
        */
        out.add(new WebSocketTunnelRequest((byte) 1, (byte) 1, (byte) 1, "127.0.0.1", 80));
    }

}
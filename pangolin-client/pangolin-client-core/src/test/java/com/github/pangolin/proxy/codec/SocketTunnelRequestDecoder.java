package com.github.pangolin.proxy.codec;

import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToMessageDecoder;
import io.netty.handler.codec.socksx.v5.Socks5CommandRequest;

import java.util.List;

public class SocketTunnelRequestDecoder extends MessageToMessageDecoder<Socks5CommandRequest> {

    @Override
    protected void decode(final ChannelHandlerContext ctx, final Socks5CommandRequest msg, final List<Object> out) throws Exception {
        out.add(new WebSocketTunnelRequest(msg.version().byteValue(), msg.type().byteValue(), msg.dstAddrType().byteValue(), msg.dstAddr(), msg.dstPort()));
    }

}
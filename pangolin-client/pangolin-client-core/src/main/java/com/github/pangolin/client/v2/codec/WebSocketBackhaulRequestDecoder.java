package com.github.pangolin.client.v2.codec;

import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToMessageDecoder;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;

import java.util.List;

public class WebSocketBackhaulRequestDecoder extends MessageToMessageDecoder<TextWebSocketFrame> {

    @Override
    protected void decode(final ChannelHandlerContext ctx, final TextWebSocketFrame msg, final List<Object> out) throws Exception {

    }

}
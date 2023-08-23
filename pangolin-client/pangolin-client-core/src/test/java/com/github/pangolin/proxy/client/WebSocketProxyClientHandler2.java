package com.github.pangolin.proxy.client;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.handler.codec.ByteToMessageCodec;
import io.netty.handler.codec.MessageToByteEncoder;
import io.netty.handler.codec.MessageToMessageDecoder;
import io.netty.handler.codec.MessageToMessageEncoder;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpClientCodec;
import io.netty.handler.codec.http.websocketx.BinaryWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketClientHandshaker;
import io.netty.handler.codec.http.websocketx.WebSocketFrame;
import io.netty.util.ReferenceCountUtil;

import java.net.SocketAddress;
import java.util.List;

public class WebSocketProxyClientHandler2 extends ProxyClientHandler {
    private final WebSocketClientHandshaker handshaker;

    public WebSocketProxyClientHandler2(final SocketAddress proxyAddress, final WebSocketClientHandshaker handshaker) {
        super(proxyAddress);
        this.handshaker = handshaker;
    }

    @Override
    public void handlerAdded(final ChannelHandlerContext ctx) throws Exception {
        final ChannelPipeline cp = ctx.pipeline();
        if (null == cp.get(HttpClientCodec.class)) {
            cp.addBefore(ctx.name(), null, new HttpClientCodec());
        }
        ctx.pipeline().addAfter(ctx.name(), null, new MessageToMessageDecoder<WebSocketFrame>() {

            @Override
            protected void decode(final ChannelHandlerContext ctx, final WebSocketFrame msg, final List<Object> out) throws Exception {
                out.add(Unpooled.copiedBuffer(msg.content()));
            }
        });
        ctx.pipeline().addAfter(ctx.name(), null, new MessageToMessageEncoder<ByteBuf>() {
            @Override
            protected void encode(final ChannelHandlerContext ctx, final ByteBuf msg, final List<Object> out) throws Exception {
                if (msg.isReadable()) {
                    msg.retain();
                    out.add(new BinaryWebSocketFrame(msg));
                }
            }
        });
    }

    @Override
    public void channelActive(final ChannelHandlerContext ctx) throws Exception {
        ctx.fireChannelActive();
//        final ChannelHandlerContext ctxToUse = ctx.pipeline().context(HttpClientCodec.class);
        final Channel channelToUse = new DelegateChannel(ctx);
        // FIXME getDelegateAddress();
        handshaker.handshake(channelToUse).addListener(new ChannelFutureListener() {
            @Override
            public void operationComplete(ChannelFuture future) throws Exception {
                if (!future.isSuccess()) {
                    ctx.fireExceptionCaught(future.cause());
                }
            }
        });
    }

    protected boolean channelRead0(final ChannelHandlerContext ctx, final Object msg) throws Exception {
        final FullHttpResponse httpResponse = (FullHttpResponse) msg;
//        try {
            if (handshaker.isHandshakeComplete()) {
                throw new IllegalStateException();
            }
            handshaker.finishHandshake(ctx.channel(), httpResponse);
            return true;
//        } finally {
//            httpResponse.release();
//        }
    }

}
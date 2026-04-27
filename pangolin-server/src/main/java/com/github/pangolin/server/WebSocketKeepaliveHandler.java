package com.github.pangolin.server;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.http.websocketx.PingWebSocketFrame;
import io.netty.handler.timeout.IdleStateEvent;
import io.netty.handler.timeout.IdleStateHandler;

public class WebSocketKeepaliveHandler extends ChannelInboundHandlerAdapter {
    private final int readerIdleTimeSeconds;
    private final int writerIdleTimeSeconds;
    private final int allIdleTimeSeconds;

    public WebSocketKeepaliveHandler(final int readerIdleTimeSeconds,
                                     final int writerIdleTimeSeconds,
                                     final int allIdleTimeSeconds) {
        this.readerIdleTimeSeconds = readerIdleTimeSeconds;
        this.writerIdleTimeSeconds = writerIdleTimeSeconds;
        this.allIdleTimeSeconds = allIdleTimeSeconds;
    }

    @Override
    public void handlerAdded(final ChannelHandlerContext ctx) throws Exception {
        ctx.pipeline().addBefore(ctx.name(), "idle", new IdleStateHandler(readerIdleTimeSeconds, writerIdleTimeSeconds, allIdleTimeSeconds));
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void userEventTriggered(final ChannelHandlerContext ctx, Object evt) throws Exception {
        if (evt instanceof IdleStateEvent) {
            ctx.writeAndFlush(new PingWebSocketFrame());
        }
        ctx.fireUserEventTriggered(evt);
    }

}

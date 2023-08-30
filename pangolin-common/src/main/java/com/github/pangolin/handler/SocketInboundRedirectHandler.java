package com.github.pangolin.handler;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.util.ReferenceCountUtil;
import lombok.extern.slf4j.Slf4j;

import java.nio.charset.StandardCharsets;

/**
 * socket to socket.
 */
@Slf4j
public class SocketInboundRedirectHandler extends ChannelInboundHandlerAdapter {
    private final ChannelHandlerContext outCtx;

    public SocketInboundRedirectHandler(final ChannelHandlerContext outCtx) {
        this.outCtx = outCtx;
    }

    @Override
    public void channelInactive(final ChannelHandlerContext inCtx) throws Exception {
        if (outCtx.channel().isActive()) {
            log.info("[tun@tcp {} => {}] Connection closed", stringify(inCtx), stringify(outCtx));
            outCtx.channel().writeAndFlush(Unpooled.EMPTY_BUFFER).addListener(ChannelFutureListener.CLOSE);
        }
    }

    @Override
    public void channelRead(final ChannelHandlerContext inCtx, final Object msg) throws Exception {
        if (outCtx.channel().isActive()) {
            if (log.isTraceEnabled()) {
                final Object msgToLog = msg instanceof ByteBuf ? ((ByteBuf) msg).toString(StandardCharsets.UTF_8) : msg;
                log.trace("[tun@tcp {} => {}] {}", stringify(inCtx), stringify(outCtx), msgToLog);
            }
            outCtx.writeAndFlush(msg);
        } else {
            ReferenceCountUtil.release(msg);
            log.error("[tun@tcp {} => {}] Connection lost: The Output closed the connection, the input will be closed", stringify(inCtx), stringify(outCtx));
            outCtx.channel().writeAndFlush(Unpooled.EMPTY_BUFFER).addListener(ChannelFutureListener.CLOSE);
        }
    }

    @Override
    public void exceptionCaught(final ChannelHandlerContext inCtx, final Throwable cause) throws Exception {
        log.error("[tun@tcp {} => {}] Software caused connection abort: {}", stringify(inCtx), stringify(outCtx), cause.getMessage(), cause);
        inCtx.channel().writeAndFlush(Unpooled.EMPTY_BUFFER).addListener(ChannelFutureListener.CLOSE);
        outCtx.channel().writeAndFlush(Unpooled.EMPTY_BUFFER).addListener(ChannelFutureListener.CLOSE);
    }

    private String stringify(final ChannelHandlerContext ctx) {
        return ctx.channel().remoteAddress().toString();
    }
}

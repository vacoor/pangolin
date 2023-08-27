package com.github.pangolin.handler;

import com.github.pangolin.util.Channels;
import com.github.pangolin.util.WebSocketUtils;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.http.websocketx.BinaryWebSocketFrame;
import io.netty.util.ReferenceCountUtil;
import lombok.extern.slf4j.Slf4j;

import java.nio.charset.StandardCharsets;

/**
 * socket over websocket.
 */
@Slf4j
public class SocketOverWebSocketEncodeHandler extends ChannelInboundHandlerAdapter {
    private final ChannelHandlerContext outCtx;

    public SocketOverWebSocketEncodeHandler(final ChannelHandlerContext wsCtx) {
        this.outCtx = wsCtx;
    }

    @Override
    public void channelInactive(final ChannelHandlerContext inCtx) {
        if (outCtx.channel().isActive()) {
            log.info("[tun@tcp/ws {} => {}] Connection closed", stringify(inCtx), stringify(outCtx));
            WebSocketUtils.normalClose(outCtx, "Connection closed");
        }
    }

    @Override
    public void channelRead(final ChannelHandlerContext inCtx, final Object msg) throws Exception {
        if (outCtx.channel().isActive()) {
            if (msg instanceof ByteBuf) {
                if (log.isDebugEnabled()) {
                    final String msgToLog = ((ByteBuf) msg).toString(StandardCharsets.UTF_8);
                    log.debug("[tun@tcp/ws {} => {}] {}", stringify(inCtx), stringify(outCtx), msgToLog);
                }
                outCtx.writeAndFlush(new BinaryWebSocketFrame((ByteBuf) msg));
            } else {
                ReferenceCountUtil.release(msg);
                throw new UnsupportedOperationException("Unexpect socket message: " + msg);
            }
        } else {
            ReferenceCountUtil.release(msg);
            log.error("[tun@tcp/ws {} => {}] Connection lost: The Output closed the connection, the input will be closed", stringify(inCtx), stringify(outCtx));
            Channels.closeOnFlush(inCtx.channel());
        }
    }

    @Override
    public void exceptionCaught(final ChannelHandlerContext inCtx, final Throwable cause) throws Exception {
        log.error("[tun@tcp/ws {} => {}] Software caused connection abort: {}", stringify(inCtx), stringify(outCtx), cause.getMessage(), cause);
        WebSocketUtils.internalErrorClose(outCtx, cause.getMessage());
        Channels.closeOnFlush(inCtx.channel());
    }

    private String stringify(final ChannelHandlerContext ctx) {
        return ctx.channel().remoteAddress().toString();
    }
}

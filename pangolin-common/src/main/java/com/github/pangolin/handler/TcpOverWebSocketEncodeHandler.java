package com.github.pangolin.handler;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.http.websocketx.BinaryWebSocketFrame;
import io.netty.handler.codec.http.websocketx.CloseWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketCloseStatus;
import io.netty.util.ReferenceCountUtil;
import lombok.extern.slf4j.Slf4j;

import java.net.SocketAddress;
import java.nio.charset.StandardCharsets;

/**
 * socket over websocket.
 */
@Slf4j
public class TcpOverWebSocketEncodeHandler extends ChannelInboundHandlerAdapter {
    private final ChannelHandlerContext outCtx;

    public TcpOverWebSocketEncodeHandler(final ChannelHandlerContext wsCtx) {
        this.outCtx = wsCtx;
    }

    @Override
    public void channelInactive(final ChannelHandlerContext inCtx) {
        log.debug("[tun@tcp/ws {} => {}] Connection closed", stringify(inCtx), stringify(outCtx));
        if (outCtx.channel().isActive()) {
            outCtx.channel().writeAndFlush(new CloseWebSocketFrame(WebSocketCloseStatus.NORMAL_CLOSURE)).addListener(ChannelFutureListener.CLOSE);
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
            log.warn("[tun@tcp/ws {} => {}] Connection lost", stringify(inCtx), stringify(outCtx));
            inCtx.channel().writeAndFlush(Unpooled.EMPTY_BUFFER).addListener(ChannelFutureListener.CLOSE);
        }
    }

    @Override
    public void exceptionCaught(final ChannelHandlerContext inCtx, final Throwable cause) throws Exception {
        if (log.isDebugEnabled()) {
            log.debug("[tun@tcp/ws {} => {}] Software caused connection abort: {}", stringify(inCtx), stringify(outCtx), cause.getMessage(), cause);
        }
        log.warn("[tun@tcp/ws {} => {}] Software caused connection abort: {}", stringify(inCtx), stringify(outCtx));

        if (outCtx.channel().isActive()) {
            outCtx.channel().writeAndFlush(new CloseWebSocketFrame(WebSocketCloseStatus.INTERNAL_SERVER_ERROR, cause.getMessage())).addListener(ChannelFutureListener.CLOSE);
        }
        if (inCtx.channel().isActive()) {
            inCtx.channel().writeAndFlush(Unpooled.EMPTY_BUFFER).addListener(ChannelFutureListener.CLOSE);
        }
    }

    private String stringify(final ChannelHandlerContext ctx) {
        final SocketAddress ra = ctx.channel().remoteAddress();
        return null != ra ? ra.toString() : "?";
    }
}

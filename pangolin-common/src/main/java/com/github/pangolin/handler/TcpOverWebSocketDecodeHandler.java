package com.github.pangolin.handler;

import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.http.websocketx.*;
import io.netty.util.ReferenceCountUtil;
import lombok.extern.slf4j.Slf4j;

import java.net.SocketAddress;
import java.nio.charset.StandardCharsets;

/**
 * socket over websocket.
 */
@Slf4j
public class TcpOverWebSocketDecodeHandler extends ChannelInboundHandlerAdapter {
    private final ChannelHandlerContext outCtx;

    public TcpOverWebSocketDecodeHandler(final ChannelHandlerContext socketCtx) {
        this.outCtx = socketCtx;
    }

    @Override
    public void channelInactive(final ChannelHandlerContext inCtx) {
        log.debug("[tun@ws/tcp {}(!) => {} Connection closed", stringify(inCtx), stringify(outCtx));

        if (outCtx.channel().isActive()) {
            outCtx.channel().writeAndFlush(Unpooled.EMPTY_BUFFER).addListener(ChannelFutureListener.CLOSE);
        }
    }

    @Override
    public void channelRead(final ChannelHandlerContext inCtx, final Object msg) {
        if (outCtx.channel().isActive()) {
            if (msg instanceof CloseWebSocketFrame) {
                final CloseWebSocketFrame c = (CloseWebSocketFrame) msg;

                log.info("[tun@ws/tcp {}(!) => {}] Connection closed by peer: {}({})", stringify(inCtx), stringify(outCtx), c.reasonText(), c.statusCode());

                ReferenceCountUtil.release(msg);
                outCtx.channel().writeAndFlush(Unpooled.EMPTY_BUFFER).addListener(ChannelFutureListener.CLOSE);
                inCtx.channel().writeAndFlush(Unpooled.EMPTY_BUFFER).addListener(ChannelFutureListener.CLOSE);
            } else if (msg instanceof PingWebSocketFrame) {
                log.debug("[tun@ws/tcp {} => {}] Ping <==", stringify(inCtx), stringify(outCtx));

                // ReferenceCountUtil.release(msg);
                outCtx.channel().writeAndFlush(Unpooled.EMPTY_BUFFER);
                inCtx.channel().writeAndFlush(new PongWebSocketFrame(((WebSocketFrame) msg).content()));
            } else if (msg instanceof PongWebSocketFrame) {
                ReferenceCountUtil.release(msg);
            } else if (msg instanceof WebSocketFrame) {
                if (log.isDebugEnabled()) {
                    final String msgToLog = ((WebSocketFrame) msg).content().toString(StandardCharsets.UTF_8);
                    log.debug("[tun@ws/tcp {} => {}] {}", stringify(inCtx), stringify(outCtx), msgToLog);
                }
                outCtx.writeAndFlush(((WebSocketFrame) msg).content());
            } else {
                ReferenceCountUtil.release(msg);

                // XXX
                log.warn("Unexpect websocket message: {}, will be closed", msg);
                outCtx.channel().writeAndFlush(Unpooled.EMPTY_BUFFER).addListener(ChannelFutureListener.CLOSE);
                inCtx.channel().writeAndFlush(new CloseWebSocketFrame(WebSocketCloseStatus.INVALID_PAYLOAD_DATA)).addListener(ChannelFutureListener.CLOSE);
            }
        } else {
            ReferenceCountUtil.release(msg);
            log.warn("[tun@ws/tcp {} => {}] Connection lost", stringify(inCtx), stringify(outCtx));
            inCtx.channel().writeAndFlush(new CloseWebSocketFrame(WebSocketCloseStatus.ENDPOINT_UNAVAILABLE, "Connection lost")).addListener(ChannelFutureListener.CLOSE);
        }
    }

    @Override
    public void exceptionCaught(final ChannelHandlerContext inCtx, final Throwable cause) {
        if (log.isDebugEnabled()) {
            log.debug("[tun@ws/tcp {} => {}] Software caused connection abort: {}", stringify(inCtx), stringify(outCtx), cause.getMessage(), cause);
        }
        log.warn("[tun@ws/tcp {} => {}] Software caused connection abort: {}", stringify(inCtx), stringify(outCtx));

        if (outCtx.channel().isActive()) {
            outCtx.channel().writeAndFlush(Unpooled.EMPTY_BUFFER).addListener(ChannelFutureListener.CLOSE);
        }
        if (inCtx.channel().isActive()) {
            inCtx.channel().writeAndFlush(new CloseWebSocketFrame(WebSocketCloseStatus.INTERNAL_SERVER_ERROR, cause.getMessage())).addListener(ChannelFutureListener.CLOSE);
        }
    }

    private String stringify(final ChannelHandlerContext ctx) {
        final SocketAddress ra = ctx.channel().remoteAddress();
        return null != ra ? ra.toString() : "?";
    }
}

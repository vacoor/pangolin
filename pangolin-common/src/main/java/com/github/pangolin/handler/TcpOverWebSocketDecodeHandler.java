package com.github.pangolin.handler;

import com.github.pangolin.util.WebSocketUtils;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.http.websocketx.CloseWebSocketFrame;
import io.netty.handler.codec.http.websocketx.PingWebSocketFrame;
import io.netty.handler.codec.http.websocketx.PongWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketFrame;
import io.netty.util.ReferenceCountUtil;
import lombok.extern.slf4j.Slf4j;

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
        if (outCtx.channel().isActive()) {
            log.error("[tun@ws/tcp {}(!) => {} Connection lost: The input closed the connection, the output will be closed", stringify(inCtx), stringify(outCtx));
            outCtx.channel().writeAndFlush(Unpooled.EMPTY_BUFFER).addListener(ChannelFutureListener.CLOSE);
        }
    }

    @Override
    public void channelRead(final ChannelHandlerContext inCtx, final Object msg) {
        if (outCtx.channel().isActive()) {
            if (msg instanceof CloseWebSocketFrame) {
                final CloseWebSocketFrame c = (CloseWebSocketFrame) msg;

                log.info("[tun@ws/tcp {}(!) => {}] Connection closed by {}/{}", stringify(inCtx), stringify(outCtx), c.statusCode(), c.reasonText());

                ReferenceCountUtil.release(msg);
                outCtx.channel().writeAndFlush(Unpooled.EMPTY_BUFFER).addListener(ChannelFutureListener.CLOSE);
                inCtx.channel().writeAndFlush(Unpooled.EMPTY_BUFFER).addListener(ChannelFutureListener.CLOSE);
            } else if (msg instanceof PingWebSocketFrame) {
                log.debug("[tun@ws/tcp {} => {}] Ping <==", stringify(inCtx), stringify(outCtx));

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
                log.error("Unexpect websocket message: {}, will be closed", msg);
                outCtx.channel().writeAndFlush(Unpooled.EMPTY_BUFFER).addListener(ChannelFutureListener.CLOSE);
                WebSocketUtils.unsupportedDataClose(inCtx, "Unexpect websocket message");
            }
        } else {
            ReferenceCountUtil.release(msg);
            log.error("[tun@ws/tcp {} => {}] Connection lost: The Output closed the connection, the input will be closed", stringify(inCtx), stringify(outCtx));
            WebSocketUtils.goingAwayClose(inCtx, "Connection lost");
        }
    }

    @Override
    public void exceptionCaught(final ChannelHandlerContext inCtx, final Throwable cause) {
        log.error("[tun@ws/tcp {} => {}] Software caused connection abort: {}", stringify(inCtx), stringify(outCtx), cause.getMessage(), cause);

        outCtx.channel().writeAndFlush(Unpooled.EMPTY_BUFFER).addListener(ChannelFutureListener.CLOSE);
        WebSocketUtils.internalErrorClose(inCtx, cause.getMessage());
    }

    private String stringify(final ChannelHandlerContext ctx) {
        return ctx.channel().remoteAddress().toString();
    }
}

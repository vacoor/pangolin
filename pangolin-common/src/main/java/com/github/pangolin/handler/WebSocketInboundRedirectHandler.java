package com.github.pangolin.handler;

import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelPipeline;
import io.netty.handler.codec.MessageToMessageDecoder;
import io.netty.handler.codec.http.websocketx.CloseWebSocketFrame;
import io.netty.handler.codec.http.websocketx.PingWebSocketFrame;
import io.netty.handler.codec.http.websocketx.PongWebSocketFrame;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketClientProtocolHandler;
import io.netty.handler.codec.http.websocketx.WebSocketCloseStatus;
import io.netty.handler.codec.http.websocketx.WebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolHandler;
import io.netty.util.ReferenceCountUtil;
import lombok.extern.slf4j.Slf4j;

import java.nio.charset.StandardCharsets;
import java.util.List;

@Slf4j
public class WebSocketInboundRedirectHandler extends ChannelInboundHandlerAdapter {
    private final ChannelHandlerContext outCtx;

    public WebSocketInboundRedirectHandler(final ChannelHandlerContext outCtx) {
        this.outCtx = outCtx;
    }

    @Override
    public void handlerAdded(final ChannelHandlerContext webSocketContext) throws Exception {
        final ChannelPipeline cp = webSocketContext.pipeline();
        final ChannelHandlerContext context = cp.context(WebSocketServerProtocolHandler.class);
        final ChannelHandlerContext contextToUse = null != context ? context : cp.context(WebSocketClientProtocolHandler.class);
        if (null != contextToUse && null == cp.get("WsCloser")) {
            cp.addBefore(contextToUse.name(), "WsCloser", new MessageToMessageDecoder<CloseWebSocketFrame>() {
                @Override
                protected void decode(final ChannelHandlerContext ctx, final CloseWebSocketFrame c, final List<Object> out) throws Exception {
                    closeGracefully(c, ctx, outCtx);
                }
            });
        }
    }

    @Override
    public void channelInactive(final ChannelHandlerContext inCtx) {
        if (outCtx.channel().isActive()) {
            log.error("[tun@ws {}(!) => {}] Connection lost: The input closed the connection, the output will be closed", stringify(inCtx), stringify(outCtx));
            outCtx.channel().writeAndFlush(new CloseWebSocketFrame(WebSocketCloseStatus.NORMAL_CLOSURE)).addListener(ChannelFutureListener.CLOSE);
        }
    }

    @Override
    public void channelRead(final ChannelHandlerContext inCtx, final Object msg) {
        if (outCtx.channel().isActive()) {
            if (log.isDebugEnabled()) {
                Object msgToLog = msg;
                if (msg instanceof TextWebSocketFrame) {
                    msgToLog = ((TextWebSocketFrame) msg).text();
                } else if (msg instanceof PingWebSocketFrame) {
                    msgToLog = "[PING]";
                } else if (msg instanceof PongWebSocketFrame) {
                    msgToLog = "[PONG]";
                } else if (msg instanceof CloseWebSocketFrame) {
                    final CloseWebSocketFrame c = (CloseWebSocketFrame) msg;
                    msgToLog = "[CLOSE] " + c.statusCode() + "/" + c.reasonText();
                } else if (msg instanceof WebSocketFrame) {
                    msgToLog = ((WebSocketFrame) msg).content().toString(StandardCharsets.UTF_8);
                }
                log.debug("[tun@ws {} => {}] {}", stringify(inCtx), stringify(outCtx), msgToLog);
            }

            /*-
             * 对于 CloseWebSocketFrame:
             * 服务端无法收到 @see io.netty.handler.codec.http.websocketx.WebSocketServerProtocolHandler.decode
             * 客户端需要设置 @see io.netty.handler.codec.http.websocketx.WebSocketClientProtocolHandler#handleCloseFrames
             * 这里通过 #handlerAdded 来处理
             */
            if (!(msg instanceof CloseWebSocketFrame)) {
                outCtx.writeAndFlush(msg);
            } else {
                closeGracefully((CloseWebSocketFrame) msg, inCtx, outCtx);
            }
        } else {
            ReferenceCountUtil.release(msg);
            log.error("[tun@ws {} => {}(!)] Output has been closed, input will be closed", stringify(inCtx), stringify(outCtx));
            inCtx.channel().writeAndFlush(new CloseWebSocketFrame(WebSocketCloseStatus.NORMAL_CLOSURE)).addListener(ChannelFutureListener.CLOSE);
        }
    }

    private void closeGracefully(final CloseWebSocketFrame c, final ChannelHandlerContext inCtx, final ChannelHandlerContext outCtx) {
        log.info("[tun@ws {}(!) => {}] Connection closed by {}/{}", stringify(inCtx), stringify(outCtx), c.statusCode(), c.reasonText());
        if (outCtx.channel().isActive()) {
            outCtx.writeAndFlush(c.retain()).addListener(ChannelFutureListener.CLOSE);
        } else {
            c.release();
        }
        inCtx.channel().writeAndFlush(Unpooled.EMPTY_BUFFER).addListener(ChannelFutureListener.CLOSE);
    }

    @Override
    public void exceptionCaught(final ChannelHandlerContext inCtx, final Throwable cause) {
        if (log.isDebugEnabled()) {
            log.debug("[tun@ws {} => {}] Software caused connection abort: {}", stringify(inCtx), stringify(outCtx), cause.getMessage(), cause);
        }
        log.warn("[tun@ws {} => {}] Software caused connection abort: {}", stringify(inCtx), stringify(outCtx));

        inCtx.channel().writeAndFlush(new CloseWebSocketFrame(WebSocketCloseStatus.INTERNAL_SERVER_ERROR, cause.getMessage())).addListener(ChannelFutureListener.CLOSE);
        outCtx.channel().writeAndFlush(new CloseWebSocketFrame(WebSocketCloseStatus.INTERNAL_SERVER_ERROR, cause.getMessage())).addListener(ChannelFutureListener.CLOSE);
    }

    private static String stringify(final ChannelHandlerContext ctx) {
        return ctx.channel().remoteAddress().toString();
    }
}
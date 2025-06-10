package com.github.pangolin.handler;

import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.handler.codec.MessageToMessageDecoder;
import io.netty.handler.codec.http.websocketx.*;
import io.netty.util.ReferenceCountUtil;
import lombok.extern.slf4j.Slf4j;

import java.net.SocketAddress;
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
        log.debug("[tun@ws {}(!) => {}] Connection closed", stringify(inCtx), stringify(outCtx));

        if (outCtx.channel().isActive()) {
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
            log.warn("[tun@ws {} => {}(!)] Connection lost", stringify(inCtx), stringify(outCtx));

            inCtx.channel().writeAndFlush(new CloseWebSocketFrame(WebSocketCloseStatus.NORMAL_CLOSURE)).addListener(ChannelFutureListener.CLOSE);
        }
    }

    private void closeGracefully(final CloseWebSocketFrame c, final ChannelHandlerContext inCtx, final ChannelHandlerContext outCtx) {
        log.info("[tun@ws {}(!) => {}] Connection closed by peer: {}({})", stringify(inCtx), stringify(outCtx), c.reasonText(), c.statusCode());
        if (outCtx.channel().isActive()) {
            outCtx.writeAndFlush(c.retain()).addListener(ChannelFutureListener.CLOSE);
        } else {
            c.release();
        }
        if (inCtx.channel().isActive()) {
            inCtx.channel().writeAndFlush(Unpooled.EMPTY_BUFFER).addListener(ChannelFutureListener.CLOSE);
        }
    }

    @Override
    public void exceptionCaught(final ChannelHandlerContext inCtx, final Throwable cause) {
        if (log.isDebugEnabled()) {
            log.debug("[tun@ws {} => {}] Software caused connection abort: {}", stringify(inCtx), stringify(outCtx), cause.getMessage(), cause);
        }
        log.warn("[tun@ws {} => {}] Software caused connection abort: {}", stringify(inCtx), stringify(outCtx));

        if (inCtx.channel().isActive()) {
            inCtx.channel().writeAndFlush(new CloseWebSocketFrame(WebSocketCloseStatus.INTERNAL_SERVER_ERROR, cause.getMessage())).addListener(ChannelFutureListener.CLOSE);
        }
        if (outCtx.channel().isActive()) {
            outCtx.channel().writeAndFlush(new CloseWebSocketFrame(WebSocketCloseStatus.INTERNAL_SERVER_ERROR, cause.getMessage())).addListener(ChannelFutureListener.CLOSE);
        }
    }

    private static String stringify(final ChannelHandlerContext ctx) {
        final SocketAddress ra = ctx.channel().remoteAddress();
        return null != ra ? ra.toString() : "?";
    }
}
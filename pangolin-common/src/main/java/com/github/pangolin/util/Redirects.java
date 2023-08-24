package com.github.pangolin.util;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandler;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelPipeline;
import io.netty.handler.codec.MessageToMessageDecoder;
import io.netty.handler.codec.http.websocketx.BinaryWebSocketFrame;
import io.netty.handler.codec.http.websocketx.CloseWebSocketFrame;
import io.netty.handler.codec.http.websocketx.ContinuationWebSocketFrame;
import io.netty.handler.codec.http.websocketx.PingWebSocketFrame;
import io.netty.handler.codec.http.websocketx.PongWebSocketFrame;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketClientProtocolHandler;
import io.netty.handler.codec.http.websocketx.WebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolHandler;
import io.netty.handler.timeout.IdleStateEvent;
import io.netty.util.ReferenceCountUtil;
import lombok.extern.slf4j.Slf4j;

import java.nio.charset.StandardCharsets;
import java.util.List;

@Slf4j
public abstract class Redirects {

    private Redirects() {
    }

    public static ChannelInboundHandler socketRedirectToSocket(final ChannelHandlerContext outCtx) {

        return new SocketInboundHandlerAdaptor() {
            @Override
            public void channelInactive(final ChannelHandlerContext inCtx) {
                if (outCtx.channel().isActive()) {
                    log.info("[tun@tcp {} => {}] Connection closed", stringify(inCtx), stringify(outCtx));
                    Channels.closeOnFlush(outCtx.channel());
                }
            }

            @Override
            public void channelRead(final ChannelHandlerContext inCtx, final Object msg) throws Exception {
                if (outCtx.channel().isActive()) {
                    if (log.isDebugEnabled()) {
                        final Object msgToLog = msg instanceof ByteBuf ? ((ByteBuf) msg).toString(StandardCharsets.UTF_8) : msg;
                        log.debug("[tun@tcp {} => {}] {}", stringify(inCtx), stringify(outCtx), msgToLog);
                    }
                    outCtx.writeAndFlush(msg);
                } else {
                    ReferenceCountUtil.release(msg);
                    log.error("[tun@tcp {} => {}] Connection lost: The Output closed the connection, the input will be closed", stringify(inCtx), stringify(outCtx));
                    Channels.closeOnFlush(outCtx.channel());
                }
            }

            @Override
            public void exceptionCaught(final ChannelHandlerContext inCtx, final Throwable cause) throws Exception {
                log.error("[tun@tcp {} => {}] Software caused connection abort: {}", stringify(inCtx), stringify(outCtx), cause.getMessage(), cause);
                Channels.closeOnFlush(inCtx.channel());
                Channels.closeOnFlush(outCtx.channel());
            }
        };
    }

    public static ChannelInboundHandler socketRedirectToWebSocket(final ChannelHandlerContext outCtx) {
        return new SocketInboundHandlerAdaptor() {

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
                Channels.closeOnFlush(inCtx.channel());
                WebSocketUtils.internalErrorClose(outCtx, cause.getMessage());
            }
        };
    }


    public static ChannelInboundHandler webSocketRedirectToSocket(final ChannelHandlerContext outCtx) {
        return new WebSocketInboundHandlerAdaptor() {

            @Override
            public void channelInactive(final ChannelHandlerContext inCtx) {
                if (outCtx.channel().isActive()) {
                    log.error("[tun@ws/tcp {}(!) => {} Connection lost: The input closed the connection, the output will be closed", stringify(inCtx), stringify(outCtx));
                    Channels.closeOnFlush(outCtx.channel());
                }
            }

            @Override
            public void channelRead(final ChannelHandlerContext inCtx, final Object msg) {
                if (outCtx.channel().isActive()) {
                    if (msg instanceof CloseWebSocketFrame) {
                        final CloseWebSocketFrame c = (CloseWebSocketFrame) msg;

                        log.info("[tun@ws/tcp {}(!) => {}] Connection closed by {}/{}", stringify(inCtx), stringify(outCtx), c.statusCode(), c.reasonText());

                        ReferenceCountUtil.release(msg);
                        Channels.closeOnFlush(inCtx.channel());
                        Channels.closeOnFlush(outCtx.channel());
                    } else if (msg instanceof BinaryWebSocketFrame || msg instanceof TextWebSocketFrame || msg instanceof ContinuationWebSocketFrame) {
                        if (log.isDebugEnabled()) {
                            final String msgToLog = ((WebSocketFrame) msg).content().toString(StandardCharsets.UTF_8);
                            log.debug("[tun@ws/tcp {} => {}] {}", stringify(inCtx), stringify(outCtx), msgToLog);
                        }
                        outCtx.writeAndFlush(((WebSocketFrame) msg).content());
                    } else if ((msg instanceof PingWebSocketFrame) || (msg instanceof PongWebSocketFrame)) {
                        ReferenceCountUtil.release(msg);
                    } else {
                        throw new UnsupportedOperationException("Unexpect websocket message: " + msg);
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

                WebSocketUtils.internalErrorClose(inCtx, cause.getMessage());
                Channels.closeOnFlush(outCtx.channel());
            }
        };
    }

    private static String stringify(final ChannelHandlerContext ctx) {
        return ctx.channel().remoteAddress().toString();
    }

    public static ChannelInboundHandler webSocketRedirectToWebSocket(final ChannelHandlerContext outCtx) {
        return new WebSocketInboundHandlerAdaptor() {

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
                    WebSocketUtils.goingAwayClose(outCtx, "Connection lost");
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
                    WebSocketUtils.goingAwayClose(inCtx, "Connection lost");
                }
            }

            private void closeGracefully(final CloseWebSocketFrame c, final ChannelHandlerContext inCtx, final ChannelHandlerContext outCtx) {
                log.info("[tun@ws {}(!) => {}] Connection closed by {}/{}", stringify(inCtx), stringify(outCtx), c.statusCode(), c.reasonText());
                if (outCtx.channel().isActive()) {
                    outCtx.writeAndFlush(c).addListener(ChannelFutureListener.CLOSE);
                } else {
                    c.release();
                }
                Channels.closeOnFlush(inCtx.channel());
            }

            @Override
            public void exceptionCaught(final ChannelHandlerContext inCtx, final Throwable cause) {
                log.error("[tun@ws {}(!) =>{}] Software caused connection abort: {}", stringify(inCtx), stringify(outCtx), cause.getMessage(), cause);
                WebSocketUtils.internalErrorClose(inCtx, cause.getMessage());
                WebSocketUtils.internalErrorClose(outCtx, cause.getMessage());
            }
        };
    }

    private static abstract class SocketInboundHandlerAdaptor extends ChannelInboundHandlerAdapter {
        @Override
        public void userEventTriggered(final ChannelHandlerContext ctx, final Object evt) throws Exception {
            if (evt instanceof IdleStateEvent) {
                ctx.writeAndFlush(Unpooled.EMPTY_BUFFER);
            }
            super.userEventTriggered(ctx, evt);
        }

        @Override
        public void channelActive(final ChannelHandlerContext ctx) {
            ctx.writeAndFlush(Unpooled.EMPTY_BUFFER);
        }
    }

    private static abstract class WebSocketInboundHandlerAdaptor extends ChannelInboundHandlerAdapter {
        @Override
        public void userEventTriggered(final ChannelHandlerContext ctx, final Object evt) throws Exception {
            if (evt instanceof IdleStateEvent) {
                ctx.writeAndFlush(new PingWebSocketFrame());
            }
            super.userEventTriggered(ctx, evt);
        }

        @Override
        public void channelActive(final ChannelHandlerContext webSocketContext) {
            webSocketContext.writeAndFlush(Unpooled.EMPTY_BUFFER);
        }

    }
}
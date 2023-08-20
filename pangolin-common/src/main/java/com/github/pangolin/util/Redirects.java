package com.github.pangolin.util;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.handler.codec.MessageToMessageDecoder;
import io.netty.handler.codec.http.websocketx.*;
import io.netty.handler.timeout.IdleStateEvent;
import io.netty.util.ReferenceCountUtil;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

@Slf4j
public abstract class Redirects {

    private Redirects() {
    }

    public static ChannelInboundHandler socketRedirectToSocket(final ChannelHandlerContext targetSocketContext) {
        return new SocketInboundHandlerAdaptor() {
            @Override
            public void channelInactive(final ChannelHandlerContext nativeSocketContext) {
                if (targetSocketContext.channel().isActive()) {
                    log.debug("{} Socket <-> Socket PIPE the input has been closed, the output will be closed: {}", nativeSocketContext.channel(), targetSocketContext.channel());
                    targetSocketContext.writeAndFlush(Unpooled.EMPTY_BUFFER).addListener(ChannelFutureListener.CLOSE);
                }
            }

            @Override
            public void channelRead(final ChannelHandlerContext nativeSocketContext, final Object msg) throws Exception {
                if (targetSocketContext.channel().isActive()) {
                    targetSocketContext.writeAndFlush(msg);
                } else {
                    targetSocketContext.writeAndFlush(Unpooled.EMPTY_BUFFER).addListener(ChannelFutureListener.CLOSE);
                    ReferenceCountUtil.release(msg);
                }
            }

            @Override
            public void exceptionCaught(final ChannelHandlerContext nativeSocketContext, final Throwable cause) throws Exception {
                log.warn("{} Software caused connection abort: {}, -> {}", nativeSocketContext.channel(), cause.getMessage(), targetSocketContext.channel());
                nativeSocketContext.close();
                targetSocketContext.writeAndFlush(Unpooled.EMPTY_BUFFER).addListener(ChannelFutureListener.CLOSE);
            }
        };
    }

    public static ChannelInboundHandler socketRedirectToWebSocket(final ChannelHandlerContext webSocketContext) {
        return new SocketInboundHandlerAdaptor() {
            @Override
            public void channelInactive(final ChannelHandlerContext nativeSocketContext) {
                if (webSocketContext.channel().isActive()) {
                    log.debug("{} Socket <-> WebSocket PIPE the input has been closed, the output will be closed: {}", nativeSocketContext.channel(), webSocketContext.channel());
                    WebSocketUtils.goingAwayClose(webSocketContext, "Connection closed");
                }
            }

            @Override
            public void channelRead(final ChannelHandlerContext nativeSocketContext, final Object msg) throws Exception {
                if (webSocketContext.channel().isActive()) {
                    if (msg instanceof ByteBuf) {
                        webSocketContext.writeAndFlush(new BinaryWebSocketFrame((ByteBuf) msg));
                    } else {
                        throw new UnsupportedOperationException("Unexpect socket message: " + msg);
                    }
                } else {
                    ReferenceCountUtil.release(msg);
                }
            }

            @Override
            public void exceptionCaught(final ChannelHandlerContext nativeSocketContext, final Throwable cause) throws Exception {
                log.warn("{} Software caused connection abort: {}, -> {}", nativeSocketContext.channel(), cause.getMessage(), webSocketContext.channel());
                nativeSocketContext.close();
                WebSocketUtils.internalErrorClose(webSocketContext, cause.getMessage());
            }
        };
    }


    public static ChannelInboundHandler webSocketRedirectToSocket(final ChannelHandlerContext nativeSocketContext) {
        return new WebSocketInboundHandlerAdaptor() {

            @Override
            public void channelInactive(final ChannelHandlerContext webSocketContext) {
                if (nativeSocketContext.channel().isActive()) {
                    log.info("{} WebSocket <-> Socket PIPE the input has been closed, the output will be closed: {}", webSocketContext.channel(), nativeSocketContext.channel());
                    nativeSocketContext.writeAndFlush(Unpooled.EMPTY_BUFFER).addListener(ChannelFutureListener.CLOSE);
                }
            }

            @Override
            public void channelRead(final ChannelHandlerContext webSocketContext, final Object msg) {
                if (nativeSocketContext.channel().isActive()) {
                    if (msg instanceof BinaryWebSocketFrame) {
                        final ByteBuf buf = ((BinaryWebSocketFrame) msg).content();
                        nativeSocketContext.writeAndFlush(buf);
                    } else if (msg instanceof CloseWebSocketFrame) {
                        final CloseWebSocketFrame c = (CloseWebSocketFrame) msg;
                        log.info("{} WebSocket connection closed by {}/{}", webSocketContext.channel(), c.statusCode(), c.reasonText());
                        try {
                            webSocketContext.writeAndFlush(Unpooled.EMPTY_BUFFER).addListener(ChannelFutureListener.CLOSE);
                            nativeSocketContext.writeAndFlush(Unpooled.EMPTY_BUFFER).addListener(ChannelFutureListener.CLOSE);
                        } finally {
                            c.release();
                        }
                    } else {
                        throw new UnsupportedOperationException("Unexpect websocket message: " + msg);
                    }
                } else {
                    ReferenceCountUtil.release(msg);
                }
            }

            @Override
            public void exceptionCaught(final ChannelHandlerContext webSocketContext, final Throwable cause) {
                log.warn("{} Software caused connection abort: {}, -> {}", webSocketContext.channel(), cause.getMessage(), nativeSocketContext.channel(), cause);

                WebSocketUtils.internalErrorClose(webSocketContext, cause.getMessage());
                nativeSocketContext.close();
            }
        };
    }

    public static ChannelInboundHandler webSocketRedirectToWebSocket(final ChannelHandlerContext targetWebSocketContext) {
        return new WebSocketInboundHandlerAdaptor() {
            @Override
            public void channelInactive(final ChannelHandlerContext sourceWebSocketContext) {
                if (targetWebSocketContext.channel().isActive()) {
                    // 非正常关闭, 另一侧可能没有关闭.
                    log.warn("{} WebSocket PIPE the input has been closed, the output will be closed: {}", sourceWebSocketContext.channel(), targetWebSocketContext.channel());
                    targetWebSocketContext.writeAndFlush(Unpooled.EMPTY_BUFFER).addListener(ChannelFutureListener.CLOSE);
                }
            }

            @Override
            public void channelRead(final ChannelHandlerContext sourceWebSocketContext, final Object msg) {
                if (targetWebSocketContext.channel().isActive()) {
                    targetWebSocketContext.writeAndFlush(msg);
                } else {
                    ReferenceCountUtil.release(msg);
                }

                /*-
                 * 对于 CloseWebSocketFrame:
                 * 服务端无法收到 @see io.netty.handler.codec.http.websocketx.WebSocketServerProtocolHandler.decode
                 * 客户端需要设置 @see io.netty.handler.codec.http.websocketx.WebSocketClientProtocolHandler#handleCloseFrames
                 * 这里通过 #handlerAdded 来处理
                 */
                if (msg instanceof CloseWebSocketFrame) {
                    handleCloseFrame((CloseWebSocketFrame) msg, sourceWebSocketContext, targetWebSocketContext);
                }
            }

            private void handleCloseFrame(final CloseWebSocketFrame c,
                                          final ChannelHandlerContext sourceWebSocketContext,
                                          final ChannelHandlerContext targetWebSocketContext) {
                c.retain();
                log.info("{} WebSocket connection closed by {}/{}", sourceWebSocketContext.channel(), c.statusCode(), c.reasonText());
                if (targetWebSocketContext.channel().isActive()) {
                    targetWebSocketContext.writeAndFlush(c).addListener(ChannelFutureListener.CLOSE);
                } else {
                    c.release();
                }
                sourceWebSocketContext.writeAndFlush(Unpooled.EMPTY_BUFFER).addListener(ChannelFutureListener.CLOSE);
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
                            handleCloseFrame(c, ctx, targetWebSocketContext);
                        }
                    });
                }
            }

            @Override
            public void exceptionCaught(final ChannelHandlerContext sourceWebSocketContext, final Throwable cause) {
                log.warn("{} Software caused connection abort: {}, -> {}", sourceWebSocketContext.channel(), cause.getMessage(), targetWebSocketContext.channel(), cause);
                WebSocketUtils.internalErrorClose(sourceWebSocketContext, cause.getMessage());
                WebSocketUtils.internalErrorClose(targetWebSocketContext, cause.getMessage());
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
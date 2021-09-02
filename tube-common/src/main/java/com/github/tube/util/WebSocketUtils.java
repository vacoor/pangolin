package com.github.tube.util;

import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.websocketx.CloseWebSocketFrame;

/**
 * TODO DOC ME!.
 *
 * @author changhe.yang
 * @since 20210902
 */
public class WebSocketUtils {

    public static ChannelFuture normalClose(final ChannelHandlerContext context, final String reason) {
        return close(context, 1000, reason);
    }

    public static ChannelFuture goingAwayClose(final ChannelHandlerContext context, final String reason) {
        return close(context, 1001, reason);
    }

    public static ChannelFuture protocolErrorClose(final ChannelHandlerContext context, final String reason) {
        return close(context, 1002, reason);
    }

    public static ChannelFuture unsupportedClose(final ChannelHandlerContext context, final String reason) {
        return close(context, 1003, reason);
    }

    public static ChannelFuture unsupportedDataClose(final ChannelHandlerContext context, final String reason) {
        return close(context, 1007, reason);
    }

    public static ChannelFuture policyViolationClose(final ChannelHandlerContext context, final String reason) {
        return close(context, 1008, reason);
    }

    public static ChannelFuture internalErrorClose(final ChannelHandlerContext context, final String reason) {
        return close(context, 1011, reason);
    }


    public static ChannelFuture close(final ChannelHandlerContext context, final int code, final String reason) {
        return close(context, new CloseWebSocketFrame(code, reason));
    }

    public static ChannelFuture close(final ChannelHandlerContext context, final CloseWebSocketFrame reason) {
        return context.writeAndFlush(null != reason ? reason : Unpooled.EMPTY_BUFFER).addListener(ChannelFutureListener.CLOSE);
    }

}

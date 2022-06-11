package com.github.pangolin.util;

import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.websocketx.CloseWebSocketFrame;

/**
 * WebSocket 工具类.
 */
public abstract class WebSocketUtils {

    private WebSocketUtils() {
    }

    /**
     * 正常关闭.
     *
     * @param context 关闭的 WebSocket context
     * @param reason  关闭原因
     * @return ChannelFuture
     */
    public static ChannelFuture normalClose(final ChannelHandlerContext context, final String reason) {
        return close(context, 1000, reason);
    }

    /**
     * 终端离开关闭.
     * <p>
     * 可能因为服务端错误, 也可能因为浏览器正从打开连接的页面跳转离开.
     * </p>
     *
     * @param context 关闭的 WebSocket context
     * @param reason  关闭原因
     * @return ChannelFuture
     */
    public static ChannelFuture goingAwayClose(final ChannelHandlerContext context, final String reason) {
        return close(context, 1001, reason);
    }

    /**
     * 协议错误而中断连接.
     *
     * @param context 关闭的 WebSocket context
     * @param reason  关闭原因
     * @return ChannelFuture
     */
    public static ChannelFuture protocolErrorClose(final ChannelHandlerContext context, final String reason) {
        return close(context, 1002, reason);
    }

    /**
     * 由于接收到不允许的数据类型而断开连接.
     *
     * @param context 关闭的 WebSocket context
     * @param reason  关闭原因
     * @return ChannelFuture
     */
    public static ChannelFuture unsupportedClose(final ChannelHandlerContext context, final String reason) {
        return close(context, 1003, reason);
    }

    /**
     * 由于收到了格式不符的数据而断开连接.
     *
     * @param context 关闭的 WebSocket context
     * @param reason  关闭原因
     * @return ChannelFuture
     */
    public static ChannelFuture unsupportedDataClose(final ChannelHandlerContext context, final String reason) {
        return close(context, 1007, reason);
    }

    /**
     * 由于收到不符合约定的数据而断开连接.
     *
     * @param context 关闭的 WebSocket context
     * @param reason  关闭原因
     * @return ChannelFuture
     */
    public static ChannelFuture policyViolationClose(final ChannelHandlerContext context, final String reason) {
        return close(context, 1008, reason);
    }

    /**
     * 遇到没有预料的情况断开连接.
     *
     * @param context 关闭的 WebSocket context
     * @param reason  关闭原因
     * @return ChannelFuture
     */
    public static ChannelFuture internalErrorClose(final ChannelHandlerContext context, final String reason) {
        return close(context, 1011, reason);
    }


    /**
     * 关闭连接.
     *
     * @param context 关闭的 WebSocket context
     * @param code    错误代码
     * @param reason  关闭原因
     * @return ChannelFuture
     */
    public static ChannelFuture close(final ChannelHandlerContext context, final int code, final String reason) {
        return close(context, new CloseWebSocketFrame(code, reason));
    }

    /**
     * 关闭连接.
     *
     * @param context 关闭的 WebSocket context
     * @param reason  关闭桢
     * @return ChannelFuture
     */
    public static ChannelFuture close(final ChannelHandlerContext context, final CloseWebSocketFrame reason) {
        return context.writeAndFlush(null != reason ? reason : Unpooled.EMPTY_BUFFER).addListener(ChannelFutureListener.CLOSE);
    }

}

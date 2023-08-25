package com.github.pangolin.util;

import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.codec.http.HttpClientCodec;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.websocketx.CloseWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketClientHandshakerFactory;
import io.netty.handler.codec.http.websocketx.WebSocketClientProtocolHandler;
import io.netty.handler.codec.http.websocketx.WebSocketVersion;
import io.netty.handler.codec.http.websocketx.extensions.compression.WebSocketClientCompressionHandler;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.timeout.IdleStateHandler;
import io.netty.util.concurrent.DefaultThreadFactory;

import javax.net.ssl.SSLException;
import java.net.URI;

/**
 * WebSocket 工具类.
 */
public abstract class WebSocketUtils {
    /**
     * 最大 HTTP 内容长度.
     */
    private static final int MAX_HTTP_CONTENT_LENGTH = 1024 * 1024 * 8;

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


    public static Channel openSocketChannel(final String host, final int port, final EventLoopGroup group, final ChannelHandler initializer) throws InterruptedException {
        return Channels.open(host, port, true, group, initializer).sync().channel();
    }

    public static Channel openWebSocketChannel(final URI webSocketEndpoint, final String webSocketProtocol, final HttpHeaders customHttpHeaders, final ChannelHandler... webSocketHandlers) throws SSLException, InterruptedException {
        final boolean isSecure = "wss".equalsIgnoreCase(webSocketEndpoint.getScheme());
        final SslContext context = isSecure ? Channels.createClientSslContext() : null;
        final int portToUse = 0 < webSocketEndpoint.getPort() ? webSocketEndpoint.getPort() : (isSecure ? 443 : 80);

        final EventLoopGroup webSocketGroup = new NioEventLoopGroup(1, new DefaultThreadFactory("WebSocket-PIPE-MASTER", true));
        return openSocketChannel(webSocketEndpoint.getHost(), portToUse, webSocketGroup, new ChannelInitializer<SocketChannel>() {
            @Override
            protected void initChannel(final SocketChannel ch) {
                final ChannelPipeline cp = ch.pipeline();
                if (null != context) {
                    cp.addLast(context.newHandler(ch.alloc()));
                }
                cp.addLast(new IdleStateHandler(0, 0, 50));
                cp.addLast(new HttpClientCodec(), new HttpObjectAggregator(MAX_HTTP_CONTENT_LENGTH));
                cp.addLast(WebSocketClientCompressionHandler.INSTANCE);
                cp.addLast(new WebSocketClientProtocolHandler(WebSocketClientHandshakerFactory.newHandshaker(
                        webSocketEndpoint, WebSocketVersion.V13, webSocketProtocol, true, customHttpHeaders, 65536, true, true
                ), false));
                cp.addLast(webSocketHandlers);
            }
        });
    }
}

package com.github.pangolin.proxy;

import com.github.pangolin.util.Channels;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.handler.codec.http.DefaultHttpHeaders;
import io.netty.handler.codec.http.HttpClientCodec;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.websocketx.WebSocketClientHandshakerFactory;
import io.netty.handler.codec.http.websocketx.WebSocketClientProtocolHandler;
import io.netty.handler.codec.http.websocketx.WebSocketVersion;
import io.netty.handler.codec.http.websocketx.extensions.compression.WebSocketClientCompressionHandler;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;

import javax.net.ssl.SSLException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.URI;
import java.nio.channels.ConnectionPendingException;

/**
 * TODO DOC ME!.
 *
 * @author changhe.yang
 * @since 20230821
 */
public class BackpressureHandler extends ChannelDuplexHandler {
    private static final int MAX_HTTP_CONTENT_LENGTH = 1024 * 1024 * 8;

    private boolean pending = false;
    private boolean flushed = false;
    private PendingWriteQueue pendingWrites;


    public BackpressureHandler() {
    }



    protected void channelProxied(final ChannelHandlerContext ctx) {
        pending = false;
        // FIXME cancel timeout
        writePendingWrites();
        if (flushed) {
            ctx.flush();
        }
    }

    protected void channelProxyFailure(final ChannelHandlerContext ctx, final Throwable cause) {
        pending = false;
        // FIXME cancel timeout
        failPendingWrites(cause);
    }

    @Override
    public void exceptionCaught(final ChannelHandlerContext ctx, final Throwable cause) throws Exception {
        if (pending) {
            // FIXME setConnectFail
            channelProxyFailure(ctx, cause);
        } else {
            ctx.fireExceptionCaught(cause);
        }
    }

    @Override
    public void write(final ChannelHandlerContext ctx, final Object msg, final ChannelPromise promise) throws Exception {
        if (pending) {
            addPendingWrite(ctx, msg, promise);
        } else {
            writePendingWrites();
            ctx.write(msg, promise);
        }
    }

    @Override
    public void flush(final ChannelHandlerContext ctx) throws Exception {
        if (pending) {
            flushed = true;
        } else {
            writePendingWrites();
            ctx.flush();
        }
    }

    private void addPendingWrite(final ChannelHandlerContext ctx, final Object msg, final ChannelPromise promise) {
        if (null == pendingWrites) {
            pendingWrites = new PendingWriteQueue(ctx);
        }
        pendingWrites.add(msg, promise);
    }

    private void writePendingWrites() {
        if (null != pendingWrites) {
            pendingWrites.removeAndWriteAll();
            pendingWrites = null;
        }
    }

    private void failPendingWrites(final Throwable cause) {
        if (null != pendingWrites) {
            pendingWrites.removeAndFailAll(cause);
            pendingWrites = null;
        }
    }

}

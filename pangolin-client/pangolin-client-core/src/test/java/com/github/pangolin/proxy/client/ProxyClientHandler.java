package com.github.pangolin.proxy.client;

import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import io.netty.channel.PendingWriteQueue;
import io.netty.util.ReferenceCountUtil;

import java.net.SocketAddress;
import java.nio.channels.ConnectionPendingException;

/**
 * TODO DOC ME!.
 *
 * @author changhe.yang
 * @since 20230821
 */
public abstract class ProxyClientHandler extends ChannelDuplexHandler {
    private boolean pending = true;
    private boolean flushed = false;
    private PendingWriteQueue pendingWrites;
    private boolean suppressChannelReadComplete;

    private final SocketAddress proxyServerAddress;
    private volatile transient SocketAddress delegateAddress;

    public ProxyClientHandler(final SocketAddress proxyServerAddress) {
        this.proxyServerAddress = proxyServerAddress;
    }

    @Override
    public void channelActive(final ChannelHandlerContext ctx) throws Exception {
        // super.channelActive(ctx);
        System.out.println();
    }

    @Override
    public void handlerAdded(final ChannelHandlerContext ctx) throws Exception {
        super.handlerAdded(ctx);
    }

    @Override
    public void connect(final ChannelHandlerContext ctx, final SocketAddress remoteAddress, final SocketAddress localAddress, final ChannelPromise promise) throws Exception {
        if (null != delegateAddress) {
            promise.setFailure(new ConnectionPendingException());
        } else {
            delegateAddress = remoteAddress;
            ctx.connect(proxyServerAddress, localAddress, promise);
        }
    }

    @Override
    public void channelRead(final ChannelHandlerContext ctx, final Object msg) throws Exception {
        if (pending) {
            suppressChannelReadComplete = true;
            Throwable cause = null;
            try {
                final boolean initialized = channelRead0(ctx, msg);
                if (initialized) {
                    channelProxied(ctx);
                }
            } catch (final Throwable t) {
                cause = t;
            } finally {
                ReferenceCountUtil.release(msg);
                if (null != cause) {
                    exceptionCaught(ctx, cause);
                }
            }
        } else {
            // Received a message after the connection has been established; pass through.
            suppressChannelReadComplete = false;
            ctx.fireChannelRead(msg);
        }
    }

    protected abstract boolean channelRead0(final ChannelHandlerContext ctx, final Object msg) throws Exception;

    @Override
    public void channelReadComplete(final ChannelHandlerContext ctx) throws Exception {
        if (suppressChannelReadComplete) {
            suppressChannelReadComplete = false;
            readIfNecessary(ctx);
        } else {
            ctx.fireChannelReadComplete();
        }
    }

    protected void readIfNecessary(final ChannelHandlerContext ctx) {
        if (!ctx.channel().config().isAutoRead()) {
            ctx.read();
        }
    }

    @Override
    public void channelInactive(final ChannelHandlerContext ctx) throws Exception {
        if (pending) {
            //
        } else {
            ctx.fireChannelInactive();
        }
    }

    protected void channelProxied(final ChannelHandlerContext ctx) {
        pending = false;
        // FIXME cancel timeout
        writePendingWrites();
        if (flushed) {
            ctx.flush();
        }
        ctx.fireChannelActive();
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

    @SuppressWarnings("unchecked")
    public <T extends SocketAddress> T getDelegateAddress() {
        return (T) delegateAddress;
    }
}

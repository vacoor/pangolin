package com.github.pangolin.proxy.client;

import io.netty.channel.*;
import io.netty.util.ReferenceCountUtil;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.FutureListener;

import java.net.ConnectException;
import java.net.SocketAddress;
import java.nio.channels.ConnectionPendingException;
import java.util.concurrent.TimeUnit;

public abstract class WsProxyHandler extends ChannelDuplexHandler {
    private final SocketAddress proxyAddress;
    private volatile SocketAddress destinationAddress;
    private PendingWriteQueue pendingWrites;
    private boolean finished;
    private boolean suppressChannelReadComplete;
    private boolean flushedPrematurely;
    private ChannelPromise handshakePromise;

    public WsProxyHandler(final SocketAddress proxyAddress) {
        this.proxyAddress = proxyAddress;
    }

    @Override
    public void connect(final ChannelHandlerContext ctx, final SocketAddress remoteAddress, final SocketAddress localAddress, ChannelPromise promise) throws Exception {
        if (null != destinationAddress) {
            promise.setFailure(new ConnectionPendingException());
        } else {
            destinationAddress = remoteAddress;
            ctx.connect(proxyAddress, localAddress, promise);
        }
    }

    @Override
    public void channelActive(final ChannelHandlerContext ctx) throws Exception {
        handshakePromise = handshake(ctx, ctx.newPromise());
        handshakePromise.addListener(new ChannelFutureListener() {
            @Override
            public void operationComplete(final ChannelFuture f) throws Exception {
                if (!f.isSuccess()) {
                    handshakeAbort(ctx, f.cause());
                }
            }
        });
        applyHandshakeTimeout(ctx, handshakePromise, ctx.channel().config().getConnectTimeoutMillis());
        ctx.fireChannelActive();
    }

    protected abstract ChannelPromise handshake(final ChannelHandlerContext ctx, final ChannelPromise promise) throws Exception;

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        if (finished) {
            ctx.fireChannelInactive();
        } else {
            handshakeAbort(ctx, new ConnectException("disconnected"));
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        if (finished) {
            ctx.fireExceptionCaught(cause);
        } else {
            handshakeAbort(ctx, cause);
        }
    }

    @Override
    public void channelRead(final ChannelHandlerContext ctx, final Object msg) throws Exception {
        if (finished) {
            suppressChannelReadComplete = false;
            ctx.fireChannelRead(msg);
        } else {
            suppressChannelReadComplete = true;
            try {
                final boolean done = handshakeRead(ctx, msg);
                if (done) {
                    // connect success
                    handshakeSuccess(ctx);
                }
            } catch (final Throwable cause) {
                // connect failure
                handshakeAbort(ctx, cause);
            } finally {
                ReferenceCountUtil.release(msg);
            }
        }
    }

    protected abstract boolean handshakeRead(final ChannelHandlerContext ctx, final Object msg) throws Exception;

    protected void handshakeSuccess(final ChannelHandlerContext ctx) throws Exception {
        finished = true;
        if (handshakePromise.trySuccess()) {
            writePendingWrites();
            if (flushedPrematurely) {
                ctx.flush();
            }
        }
    }

    protected void handshakeAbort(final ChannelHandlerContext ctx, final Throwable cause) {
        finished = true;
        if (!handshakePromise.isDone()) {
            failPendingWritesAndClose(ctx, cause);
        }
    }

    private void failPendingWritesAndClose(final ChannelHandlerContext ctx, Throwable cause) {
        failPendingWrites(cause);
        handshakePromise.tryFailure(cause);
        ctx.fireExceptionCaught(cause);
        ctx.close();
    }

    @Override
    public void channelReadComplete(final ChannelHandlerContext ctx) throws Exception {
        if (suppressChannelReadComplete) {
            suppressChannelReadComplete = false;
            readIfNeeded(ctx);
        } else {
            ctx.fireChannelReadComplete();
        }
    }

    @Override
    public final void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
        if (finished) {
            writePendingWrites();
            ctx.write(msg, promise);
        } else {
            addPendingWrite(ctx, msg, promise);
        }
    }

    @Override
    public final void flush(ChannelHandlerContext ctx) throws Exception {
        if (finished) {
            writePendingWrites();
            ctx.flush();
        } else {
            flushedPrematurely = true;
        }
    }

    private void writePendingWrites() {
        if (pendingWrites != null) {
            pendingWrites.removeAndWriteAll();
            pendingWrites = null;
        }
    }

    private void failPendingWrites(Throwable cause) {
        if (pendingWrites != null) {
            pendingWrites.removeAndFailAll(cause);
            pendingWrites = null;
        }
    }

    private void addPendingWrite(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
        PendingWriteQueue pendingWrites = this.pendingWrites;
        if (pendingWrites == null) {
            this.pendingWrites = pendingWrites = new PendingWriteQueue(ctx);
        }
        pendingWrites.add(msg, promise);
    }

    protected void readIfNeeded(final ChannelHandlerContext ctx) {
        if (!ctx.channel().config().isAutoRead()) {
            ctx.read();
        }
    }

    protected ChannelPromise applyHandshakeTimeout(final ChannelHandlerContext ctx, final ChannelPromise handshakePromise, final long handshakeTimeoutMillis) {
        if (handshakeTimeoutMillis <= 0 || handshakePromise.isDone()) {
            return handshakePromise;
        }

        final Future<?> timeoutFuture = ctx.executor().schedule(new Runnable() {
            @Override
            public void run() {
                if (!handshakePromise.isDone() && handshakePromise.tryFailure(new ConnectTimeoutException("handshake timed out"))) {
                    ctx.flush().close();
                }
            }
        }, handshakeTimeoutMillis, TimeUnit.MILLISECONDS);

        // Cancel the handshake timeout when handshake is finished.
        return handshakePromise.addListener(new FutureListener<Void>() {
            @Override
            public void operationComplete(Future<Void> f) throws Exception {
                timeoutFuture.cancel(false);
            }
        });
    }

    @SuppressWarnings("unchecked")
    public <T extends SocketAddress> T destinationAddress() {
        return (T) destinationAddress;
    }
}

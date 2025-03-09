package com.github.pangolin.routing.handler.client;

import io.netty.channel.*;
import io.netty.util.ReferenceCountUtil;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.FutureListener;
import io.netty.util.internal.ObjectUtil;

import java.net.SocketAddress;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.ConnectionPendingException;
import java.util.concurrent.TimeUnit;

/**
 * 客户端代理处理器.
 *
 * @author vacoor
 */
public abstract class AbstractProxyHandler extends ChannelDuplexHandler {
    private final SocketAddress proxyAddress;

    private volatile SocketAddress destinationAddress;
    private PendingWriteQueue pendingWrites;
    private boolean suppressChannelReadComplete;
    private boolean flushedBeforeHandshake;
    private ChannelPromise handshakePromise;

    public AbstractProxyHandler(final SocketAddress proxyAddress) {
        this.proxyAddress = ObjectUtil.checkNotNull(proxyAddress, "proxyAddress");
    }

    @Override
    public void handlerAdded(final ChannelHandlerContext ctx) throws Exception {
        if (ctx.channel().isActive()) {
            startHandshakeProcessing(ctx);
        }
    }

    @Override
    public void connect(final ChannelHandlerContext ctx, final SocketAddress remoteAddress, final SocketAddress localAddress, ChannelPromise promise) throws Exception {
        if (null != destinationAddress) {
            promise.setFailure(new ConnectionPendingException());
        } else {
            handshakePromise = ctx.newPromise().addListener(new ChannelFutureListener() {
                @Override
                public void operationComplete(final ChannelFuture f) throws Exception {
                    if (!f.isSuccess()) {
                        setHandshakeFailure(ctx, f.cause());
                        promise.tryFailure(f.cause());
                    } else {
                        promise.trySuccess();
                    }
                }
            });
            destinationAddress = remoteAddress;

            final ChannelPromise delegate = ctx.newPromise().addListener(new ChannelFutureListener() {
                @Override
                public void operationComplete(final ChannelFuture channelFuture) throws Exception {
                    if (!channelFuture.isSuccess()) {
                        handshakePromise.tryFailure(channelFuture.cause());
                    }
                }
            });

            ctx.connect(proxyAddress, localAddress, delegate);
        }
    }

    @Override
    public void channelActive(final ChannelHandlerContext ctx) throws Exception {
        startHandshakeProcessing(ctx);
        ctx.fireChannelActive();
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        if (!handshakePromise.isDone()) {
            setHandshakeFailure(ctx, new ClosedChannelException());
        }
        ctx.fireChannelInactive();
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        if (!handshakePromise.isDone()) {
            setHandshakeFailure(ctx, cause);
        }
        ctx.fireExceptionCaught(cause);
    }

    @Override
    public void channelRead(final ChannelHandlerContext ctx, final Object msg) throws Exception {
        if (handshakePromise.isDone()) {
            suppressChannelReadComplete = false;
            ctx.fireChannelRead(msg);
        } else {
            suppressChannelReadComplete = true;
            try {
                final boolean done = handshakeRead(ctx, msg);
                if (done) {
                    setHandshakeSuccess(ctx);
                }
            } catch (final Throwable cause) {
                setHandshakeFailure(ctx, cause);
            } finally {
                ReferenceCountUtil.release(msg);
            }
        }
    }

    private void startHandshakeProcessing(final ChannelHandlerContext ctx) throws Exception {
        handshake(ctx, handshakePromise);
        handshakePromise.addListener(new ChannelFutureListener() {
            @Override
            public void operationComplete(final ChannelFuture f) throws Exception {
                if (!f.isSuccess()) {
                    setHandshakeFailure(ctx, f.cause());
                }
            }
        });
        applyHandshakeTimeout(ctx, handshakePromise, ctx.channel().config().getConnectTimeoutMillis());
    }

    protected abstract ChannelPromise handshake(final ChannelHandlerContext ctx, final ChannelPromise promise) throws Exception;

    protected abstract boolean handshakeRead(final ChannelHandlerContext ctx, final Object msg) throws Exception;

    protected void setHandshakeSuccess(final ChannelHandlerContext ctx) throws Exception {
        if (!handshakePromise.isDone()) {
            writePendingWrites();
            if (flushedBeforeHandshake) {
                ctx.flush();
            }
            handshakePromise.trySuccess();
        }
    }

    protected void setHandshakeFailure(final ChannelHandlerContext ctx, final Throwable cause) {
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
        if (handshakePromise.isDone()) {
            writePendingWrites();
            ctx.write(msg, promise);
        } else {
            addPendingWrite(ctx, msg, promise);
        }
    }

    @Override
    public final void flush(ChannelHandlerContext ctx) throws Exception {
        if (handshakePromise.isDone()) {
            writePendingWrites();
            ctx.flush();
        } else {
            flushedBeforeHandshake = true;
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

    private void readIfNeeded(final ChannelHandlerContext ctx) {
        if (!ctx.channel().config().isAutoRead()) {
            ctx.read();
        }
    }

    private ChannelPromise applyHandshakeTimeout(final ChannelHandlerContext ctx, final ChannelPromise handshakePromise, final long handshakeTimeoutMillis) {
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
    protected <T extends SocketAddress> T destinationAddress() {
        return (T) destinationAddress;
    }

}

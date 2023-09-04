package com.github.pangolin.proxy.server;

import io.netty.channel.*;
import io.netty.handler.codec.http.websocketx.WebSocketHandshakeException;

public class WebSocketServerHandler extends ChannelDuplexHandler {
    private boolean finished;
    private PendingWriteQueue pendingWrites;
    private ChannelPromise handshakePromise;

    @Override
    public void write(final ChannelHandlerContext ctx, final Object msg, final ChannelPromise promise) throws Exception {
        if (finished) {
            pendingWrites.removeAndWriteAll();
            ctx.write(msg, promise);
        } else if (isHandshakeComplete(msg)) {
            if (null != handshakePromise) {
                promise.tryFailure(new WebSocketHandshakeException("Too many handshake"));
            } else {
                handshakePromise = ctx.channel().newPromise();
                beforeHandshakeComplete(handshakePromise).addListener(new ChannelFutureListener() {
                    @Override
                    public void operationComplete(final ChannelFuture channelFuture) throws Exception {
                        if (channelFuture.isSuccess()) {
                            // continue.
                        } else {
                            promise.tryFailure(channelFuture.cause());
                        }
                    }
                });
            }
        } else {
            pendingWrites.add(msg, promise);
        }
    }

    private boolean isHandshakeComplete(final Object msg) {
        return true;
    }

    ChannelFuture beforeHandshakeComplete(final ChannelPromise promise) {
        return promise;
    }

}
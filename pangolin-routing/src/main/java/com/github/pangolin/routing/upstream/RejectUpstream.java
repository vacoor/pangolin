package com.github.pangolin.routing.upstream;

import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelException;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import lombok.extern.slf4j.Slf4j;

import java.net.InetSocketAddress;
import java.net.SocketAddress;

@Slf4j
public class RejectUpstream extends AbstractUpstream {
    public static final RejectUpstream INSTANCE = new RejectUpstream();

    private RejectUpstream() {
        super("REJECT");
    }

    @Override
    public SocketAddress address() {
        return null;
    }

    @Override
    public boolean isVirtual() {
        return true;
    }

    @Override
    public ChannelHandler newSocketProxyHandler(final InetSocketAddress destination) {
        return new RejectChannelHandler(destination);
    }

    @Override
    public ChannelHandler newDatagramProxyHandler(final InetSocketAddress destination) {
        return new RejectChannelHandler(destination);
    }

    private class RejectChannelHandler extends ChannelDuplexHandler {
        private final InetSocketAddress destination;

        private RejectChannelHandler(final InetSocketAddress destination) {
            this.destination = destination;
        }

        @Override
        public void channelRegistered(final ChannelHandlerContext ctx) {
            log.info("[REJECT] {}", destination);
            ctx.channel().close();
        }

        @Override
        public void connect(final ChannelHandlerContext ctx, final SocketAddress remoteAddress,
                            final SocketAddress localAddress, final ChannelPromise promise) {
            log.info("[REJECT] connect to {}", destination);
            promise.tryFailure(new ChannelException("REJECT " + destination));
        }

        @Override
        public void bind(final ChannelHandlerContext ctx, final SocketAddress localAddress, final ChannelPromise promise) {
            log.info("[REJECT] bind to {}", destination);
            promise.tryFailure(new ChannelException("REJECT" + destination));
        }
    }
}

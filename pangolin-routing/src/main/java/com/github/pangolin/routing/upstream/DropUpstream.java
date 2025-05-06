package com.github.pangolin.routing.upstream;

import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.util.ReferenceCountUtil;
import lombok.extern.slf4j.Slf4j;

import java.net.InetSocketAddress;
import java.net.SocketAddress;

@Slf4j
public class DropUpstream extends AbstractUpstream {
    public static final DropUpstream INSTANCE = new DropUpstream();

    private DropUpstream() {
        super("DROP");
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
        return new DropChannelHandler(destination);
    }

    @Override
    public ChannelHandler newDatagramProxyHandler(final InetSocketAddress destination) {
        return new DropChannelHandler(destination);
    }

    private class DropChannelHandler extends ChannelInboundHandlerAdapter {
        private final InetSocketAddress destination;

        private DropChannelHandler(final InetSocketAddress destination) {
            this.destination = destination;
        }

        @Override
        public void channelRead(final ChannelHandlerContext ctx, final Object msg) {
            // drop msg
            log.info("[DROP] {} message: {}", destination, msg);
            ReferenceCountUtil.release(msg);
        }
    }
}

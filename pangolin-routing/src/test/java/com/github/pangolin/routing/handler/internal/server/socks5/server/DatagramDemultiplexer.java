package com.github.pangolin.routing.handler.internal.server.socks5.server;

import com.github.pangolin.routing.handler.internal.server.Socks5DatagramServerHandler;
import com.github.pangolin.routing.handler.internal.server.support.DatagramChannelFactory;
import com.google.common.collect.Maps;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandler;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.socket.DatagramPacket;
import io.netty.util.AbstractReferenceCounted;
import io.netty.util.ReferenceCounted;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.concurrent.ConcurrentMap;

/**
 * @author changhe.yang
 * @since 20240831
 */
public class DatagramDemultiplexer extends ChannelInboundHandlerAdapter {
    private final DatagramChannelFactory datagramChannelFactory;
    private final ConcurrentMap<InetAddress, HandlerWrap> handlerMap = Maps.newConcurrentMap();

    public DatagramDemultiplexer(final DatagramChannelFactory datagramChannelFactory) {
        this.datagramChannelFactory = datagramChannelFactory;
    }

    @Override
    public void channelRead(final ChannelHandlerContext ctx, final Object msg) throws Exception {
        if (msg instanceof DatagramPacket) {
            final DatagramPacket packet = (DatagramPacket) msg;
            if (!channelRead(ctx, packet)) {
                ctx.fireChannelRead(packet);
            }
        } else {
            ctx.fireChannelRead(msg);
        }
    }

    public void addHandler(final InetSocketAddress sender) {
        final InetAddress address = sender.getAddress();
        HandlerWrap handler = handlerMap.compute(address, (k, h) -> {
            if (null != h) {
                h.retain();
                return h;
            } else {
                return new HandlerWrap(new Socks5DatagramServerHandler(sender, datagramChannelFactory));
            }
        });
        System.out.println(handler);
    }

    public void removeHandler(final InetSocketAddress sender) {
        final InetAddress address = sender.getAddress();
        HandlerWrap handler = handlerMap.compute(address, (k, h) -> {
            if (null != h) {
                if (h.release()) {
                    return null;
                }
                return h;
            } else {
                return h;
            }
        });
    }

    private boolean channelRead(final ChannelHandlerContext ctx, final DatagramPacket packet) throws Exception {
        final InetAddress sender = packet.sender().getAddress();
        final ChannelInboundHandler handler = handlerMap.get(sender);
        if (null != handler) {
            handler.channelRead(ctx, packet);
            return true;
        }
        return false;
    }

    private class HandlerWrap extends AbstractReferenceCounted implements ChannelInboundHandler {
        private final ChannelInboundHandler delegate;

        private HandlerWrap(final ChannelInboundHandler delegate) {
            this.delegate = delegate;
        }

        @Override
        public void channelRegistered(final ChannelHandlerContext ctx) throws Exception {
            delegate.channelRegistered(ctx);
        }

        @Override
        public void channelUnregistered(final ChannelHandlerContext ctx) throws Exception {
            delegate.channelUnregistered(ctx);
        }

        @Override
        public void channelActive(final ChannelHandlerContext ctx) throws Exception {
            delegate.channelActive(ctx);
        }

        @Override
        public void channelInactive(final ChannelHandlerContext ctx) throws Exception {
            delegate.channelInactive(ctx);
        }

        @Override
        public void channelRead(final ChannelHandlerContext ctx, final Object msg) throws Exception {
            delegate.channelRead(ctx, msg);
        }

        @Override
        public void channelReadComplete(final ChannelHandlerContext ctx) throws Exception {
            delegate.channelReadComplete(ctx);
        }

        @Override
        public void userEventTriggered(final ChannelHandlerContext ctx, final Object evt) throws Exception {
            delegate.userEventTriggered(ctx, evt);
        }

        @Override
        public void channelWritabilityChanged(final ChannelHandlerContext ctx) throws Exception {
            delegate.channelWritabilityChanged(ctx);
        }

        @Override
        public void exceptionCaught(final ChannelHandlerContext ctx, final Throwable cause) throws Exception {
            delegate.exceptionCaught(ctx, cause);
        }

        @Override
        public void handlerAdded(final ChannelHandlerContext ctx) throws Exception {
            delegate.handlerAdded(ctx);
        }

        @Override
        public void handlerRemoved(final ChannelHandlerContext ctx) throws Exception {
            delegate.handlerRemoved(ctx);
        }

        @Override
        public ReferenceCounted touch(final Object hint) {
            return this;
        }

        @Override
        protected void deallocate() {

        }
    }

    public static void main(String[] args) {
        final DatagramDemultiplexer d = new DatagramDemultiplexer(null);
        d.addHandler(new InetSocketAddress(0));
        d.addHandler(new InetSocketAddress(0));

        d.removeHandler(new InetSocketAddress(0));
        d.removeHandler(new InetSocketAddress(0));
        System.out.println();
    }
}

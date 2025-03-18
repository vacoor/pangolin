package com.github.pangolin.routing.handler.server;

import com.github.pangolin.routing.handler.codec.socks5.Socks5ServerDatagramPacketCodec;
import com.github.pangolin.routing.support.DatagramChannelFactory;
import com.google.common.collect.Maps;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandler;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.socket.DatagramPacket;
import io.netty.util.AbstractReferenceCounted;
import io.netty.util.ReferenceCounted;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.concurrent.ConcurrentMap;

/**
 * @since 20240831
 */
// @Sharable
public class Socks5ServerDatagramDemultiplexer extends ChannelInboundHandlerAdapter {
    private final DatagramChannelFactory datagramChannelFactory;
    private final ConcurrentMap<InetAddress, HandlerRef> handlerMap = Maps.newConcurrentMap();

    public Socks5ServerDatagramDemultiplexer(final DatagramChannelFactory datagramChannelFactory) {
        this.datagramChannelFactory = datagramChannelFactory;
    }

    @Override
    public void handlerAdded(final ChannelHandlerContext ctx) throws Exception {
        final ChannelPipeline cp = ctx.pipeline();
        if (null == cp.get(Socks5ServerDatagramPacketCodec.class)) {
            cp.addBefore(ctx.name(), null, new Socks5ServerDatagramPacketCodec());
        }
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

    public void join(final InetSocketAddress sender) {
        final InetAddress address = sender.getAddress();
        HandlerRef handler = handlerMap.compute(address, (k, h) -> {
            if (null != h) {
                h.retain();
                return h;
            } else {
                return new HandlerRef(newHandler(sender));
            }
        });
        System.out.println(handler);
    }

    public void leave(final InetSocketAddress sender) {
        final InetAddress address = sender.getAddress();
        HandlerRef handler = handlerMap.compute(address, (k, h) -> {
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

    private ChannelInboundHandler newHandler(final InetSocketAddress sender) {
        return new Socks5ServerDatagramHandler(sender, datagramChannelFactory);
    }

    private boolean channelRead(final ChannelHandlerContext ctx, final DatagramPacket packet) throws Exception {
        final InetAddress sender = packet.sender().getAddress();
        final HandlerRef handler = handlerMap.get(sender);
        if (null != handler) {
            handler.delegate.channelRead(ctx, packet);
            return true;
        }
        return false;
    }

    private class HandlerRef extends AbstractReferenceCounted {
        private final ChannelInboundHandler delegate;

        private HandlerRef(final ChannelInboundHandler delegate) {
            this.delegate = delegate;
        }

        @Override
        public ReferenceCounted touch(final Object hint) {
            return this;
        }

        @Override
        protected void deallocate() {

        }
    }
}

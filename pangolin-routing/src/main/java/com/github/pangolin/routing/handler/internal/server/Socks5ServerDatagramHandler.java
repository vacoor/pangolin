package com.github.pangolin.routing.handler.internal.server;

import com.github.pangolin.routing.handler.internal.server.Socks5ServerDatagramPacketCodec;
import com.github.pangolin.routing.handler.internal.server.support.DatagramChannelFactory;
import com.google.common.collect.Maps;
import io.netty.buffer.ByteBufUtil;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.DatagramChannel;
import io.netty.channel.socket.DatagramPacket;
import lombok.extern.slf4j.Slf4j;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.concurrent.ConcurrentMap;

/**
 */
@Slf4j
public class Socks5ServerDatagramHandler extends ChannelInboundHandlerAdapter {
    private final InetSocketAddress owner;
    private final DatagramChannelFactory datagramChannelFactory;
    private final ConcurrentMap<Route, ChannelFuture> natMap = Maps.newConcurrentMap();

    public Socks5ServerDatagramHandler(final InetSocketAddress owner, final DatagramChannelFactory datagramChannelFactory) {
        this.owner = owner;
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
    public void handlerRemoved(final ChannelHandlerContext ctx) throws Exception {
        destroy();
        super.handlerRemoved(ctx);
    }

    private void destroy() {
        for (Route route : natMap.keySet()) {
            final ChannelFuture cf = natMap.remove(route);
            log.info("Destroy {}@{} -> {}", cf.channel(), route.sender, route.recipient);
            cf.channel().close();
        }
    }

    @Override
    public void channelInactive(final ChannelHandlerContext ctx) throws Exception {
        destroy();
        super.channelInactive(ctx);
    }

    @Override
    public void channelRead(final ChannelHandlerContext ctx, final Object msg) throws Exception {
        if (msg instanceof DatagramPacket) {
            DatagramPacket packet = (DatagramPacket) msg;
            InetSocketAddress sender = packet.sender();
            InetSocketAddress recipient = packet.recipient();
            InetAddress address = sender.getAddress();

            if (owner.getAddress().equals(address)) {
                final ChannelFuture cf = getChannel(sender, recipient, ctx);
                cf.sync().channel().writeAndFlush(packet);
                return;
            } else {
                log.warn("IGNORE {}", sender);
            }
        }
        super.channelRead(ctx, msg);
    }

    private ChannelFuture getChannel(final InetSocketAddress sender, final InetSocketAddress recipient, final ChannelHandlerContext context) {
        return natMap.computeIfAbsent(new Route(sender, recipient), key -> {
            return create(sender, recipient, context);
        });
    }

    private ChannelFuture create(final InetSocketAddress callback, final InetSocketAddress recipient, final ChannelHandlerContext callbackCtx) {
        return datagramChannelFactory.open(
                recipient,
                callbackCtx.channel().config().getConnectTimeoutMillis(),
//                callbackCtx.channel().eventLoop(),
                // FIXME
                new NioEventLoopGroup(),
                new ChannelInitializer<DatagramChannel>() {
                    @Override
                    protected void initChannel(final DatagramChannel ch) throws Exception {
                        ch.pipeline().addLast(new SimpleChannelInboundHandler<DatagramPacket>() {
                            @Override
                            protected void channelRead0(final ChannelHandlerContext ctx, final DatagramPacket rawPacket) throws Exception {
                                final InetSocketAddress sender = rawPacket.sender();
                                final InetSocketAddress recipient = rawPacket.recipient();
                                log.info("[UDP] {} -> {} -> {}: {}", sender, recipient, callback, ByteBufUtil.hexDump(rawPacket.content()));

                                callbackCtx.writeAndFlush(new DatagramPacket(rawPacket.content().retain(), callback, sender));
                                        // .addListener(ChannelFutureListener.CLOSE);
                            }
                        });
                    }
                });
    }

    private class Route {
        private final InetSocketAddress sender;
        private final InetSocketAddress recipient;

        private Route(final InetSocketAddress sender, final InetSocketAddress recipient) {
            this.sender = sender;
            this.recipient = recipient;
        }

        @Override
        public boolean equals(final Object o) {
            if (this == o) {
                return true;
            }
            if (o == null || getClass() != o.getClass()) {
                return false;
            }

            final Route key = (Route) o;

            if (sender != null ? !sender.equals(key.sender) : key.sender != null) {
                return false;
            }
            return recipient != null ? recipient.equals(key.recipient) : key.recipient == null;
        }

        @Override
        public int hashCode() {
            int result = sender != null ? sender.hashCode() : 0;
            result = 31 * result + (recipient != null ? recipient.hashCode() : 0);
            return result;
        }
    }
}

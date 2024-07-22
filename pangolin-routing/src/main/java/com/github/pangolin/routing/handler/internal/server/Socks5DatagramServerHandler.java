package com.github.pangolin.routing.handler.internal.server;

import com.github.pangolin.routing.handler.internal.server.support.DatagramChannelFactory;
import com.github.pangolin.routing.handler.internal.server.support.StandardDatagramChannelFactory;
import com.google.common.collect.Maps;
import io.netty.buffer.ByteBufUtil;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.DatagramChannel;
import io.netty.channel.socket.DatagramPacket;
import lombok.extern.slf4j.Slf4j;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.Map;
import java.util.concurrent.ConcurrentMap;

@Slf4j
public class Socks5DatagramServerHandler extends SimpleChannelInboundHandler<DatagramPacket> {
    private final DatagramChannelFactory datagramChannelFactory;

    public Socks5DatagramServerHandler() {
        this(new StandardDatagramChannelFactory());
    }

    public Socks5DatagramServerHandler(final DatagramChannelFactory datagramChannelFactory) {
        this.datagramChannelFactory = datagramChannelFactory;
    }

    void addToWhitelist(InetSocketAddress sender) {
        natServers.computeIfAbsent(sender.getAddress(), a -> {
            log.info("Add White: {} , {}", sender, a);
            return new OwnableServer(a);
        });
    }

    void removeFromWhitelist(InetSocketAddress sender) {
        final OwnableServer ownableServer = natServers.remove(sender.getAddress());
        if (null != ownableServer) {
            log.info("Remove White: {}", sender);
            ownableServer.shutdown();
        }
    }

    @Override
    public void handlerAdded(final ChannelHandlerContext ctx) throws Exception {
        ctx.pipeline().addBefore(ctx.name(), null, new Socks5ServerDatagramPacketCodec());
    }

    @Override
    protected void channelRead0(final ChannelHandlerContext ctx, final DatagramPacket packet) throws Exception {
        final InetSocketAddress rawSender = packet.sender();
        final InetSocketAddress rawRecipient = packet.recipient();
        final InetAddress owner = rawSender.getAddress();
        final OwnableServer ownableServer = natServers.get(owner);
        if (null == ownableServer) {
            log.warn("SKIP sender: {} -> {}, {}", rawSender, rawRecipient, natServers);
            return;
        }

        DatagramPacket packetToUse = packet.retain();
        ownableServer.writeAndFlush(packetToUse, ctx);
    }


    private ConcurrentMap<InetAddress, OwnableServer> natServers = Maps.newConcurrentMap();

    private class OwnableServer {
        private final InetAddress owner;
        private final Map<Link, ChannelFuture> natMap = Maps.newLinkedHashMap();

        private OwnableServer(final InetAddress owner) {
            this.owner = owner;
        }

        public ChannelFuture getNatMapChannel(final InetSocketAddress sender, final InetSocketAddress receipt, final ChannelHandlerContext context) {
            return natMap.computeIfAbsent(new Link(sender, receipt), key -> {
                return create(sender, receipt, context);
            });
        }

        public void writeAndFlush(final DatagramPacket packet, final ChannelHandlerContext callback) throws InterruptedException {
            getNatMapChannel(packet.sender(), packet.recipient(), callback).sync().channel().writeAndFlush(packet);
        }

        public void shutdown() {
            for (OwnableServer.Link link : natMap.keySet()) {
                ChannelFuture channel = natMap.remove(link);
                channel.channel().close();
            }
        }

        private class Link {
            private final InetSocketAddress sender;
            private final InetSocketAddress recipient;

            private Link(final InetSocketAddress sender, final InetSocketAddress recipient) {
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

                final Link key = (Link) o;

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

    private ChannelFuture create(final InetSocketAddress callback, final InetSocketAddress recipient, final ChannelHandlerContext callbackCtx) {
        return datagramChannelFactory.open(
                recipient,
                callbackCtx.channel().config().getConnectTimeoutMillis(),
//                callbackCtx.channel().eventLoop(),
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
                            }
                        });
                    }
                });
    }


}
package com.github.pangolin.routing.handler.internal.server;

import com.github.pangolin.routing.handler.internal.server.support.DatagramChannelFactory;
import com.github.pangolin.routing.handler.internal.server.support.StandardDatagramChannelFactory;
import com.github.pangolin.routing.util.SocketUtils;
import com.google.common.collect.Maps;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.DatagramChannel;
import io.netty.channel.socket.DatagramPacket;
import io.netty.handler.codec.MessageToMessageCodec;
import io.netty.handler.codec.socksx.v5.Socks5AddressDecoder;
import io.netty.handler.codec.socksx.v5.Socks5AddressEncoder;
import io.netty.handler.codec.socksx.v5.Socks5AddressType;
import lombok.extern.slf4j.Slf4j;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.List;
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

//        DatagramPacket packetToUse = decode(packet);
        DatagramPacket packetToUse = packet;
        /*-
         * UDP sync() is required.
         */
//        ownableServer.getNatMapChannel(rawSender, packetToUse.recipient(), ctx).sync().channel().writeAndFlush(packetToUse);
        ownableServer.writeAndFlush(packetToUse.retain(), ctx);
    }

    public class Socks5ServerDatagramPacketCodec extends MessageToMessageCodec<DatagramPacket, DatagramPacket> {

        @Override
        protected void encode(final ChannelHandlerContext ctx, final DatagramPacket packet, final List<Object> out) throws Exception {
            final InetSocketAddress sender = packet.sender();
            final ByteBuf rawPayload = packet.content();
            final ByteBuf payloadToReplace = Unpooled.buffer(3 + rawPayload.readableBytes() + 128);
            final Socks5AddressEncoder encoder = Socks5AddressEncoder.DEFAULT;

            // RSV, FRAG
            payloadToReplace.writeShort(0);
            payloadToReplace.writeByte(0);
            if (sender.isUnresolved()) {
                payloadToReplace.writeByte(Socks5AddressType.DOMAIN.byteValue());
                encoder.encodeAddress(Socks5AddressType.DOMAIN, sender.getHostString(), payloadToReplace);
            } else {
                InetAddress sa = sender.getAddress();
                if (sa instanceof Inet4Address) {
                    payloadToReplace.writeByte(Socks5AddressType.IPv4.byteValue());
                    encoder.encodeAddress(Socks5AddressType.IPv4, sa.getHostAddress(), payloadToReplace);
                } else if (sa instanceof Inet6Address) {
                    payloadToReplace.writeByte(Socks5AddressType.IPv6.byteValue());
                    encoder.encodeAddress(Socks5AddressType.IPv6, sa.getHostAddress(), payloadToReplace);
                } else {
                    throw new UnsupportedOperationException();
                }
            }
            payloadToReplace.writeShort(sender.getPort());
            payloadToReplace.writeBytes(rawPayload.copy());

            out.add(packet.replace(payloadToReplace));
        }

        @Override
        protected void decode(final ChannelHandlerContext ctx, final DatagramPacket packet, final List<Object> out) throws Exception {
            final Socks5AddressDecoder addressDecoder = Socks5AddressDecoder.DEFAULT;
            final InetSocketAddress sender = packet.sender();
            final InetSocketAddress recipient = packet.recipient();
        /*-
         +----+------+------+----------+----------+----------+
         |RSV | FRAG | ATYP | DST.ADDR | DST.PORT |   DATA   |
         +----+------+------+----------+----------+----------+
         | 2  |  1   |  1   | Variable |    2     | Variable |
         +----+------+------+----------+----------+----------+
         */
            final ByteBuf payload = packet.content();

            // skip RSV (0x0000), FRAG (0x00)
            final int rsv = payload.readUnsignedShort();
            final byte frag = payload.readByte();

            final Socks5AddressType dstAddrType = Socks5AddressType.valueOf(payload.readByte());
            final String dstAddr = addressDecoder.decodeAddress(dstAddrType, payload);
            final int dstPort = payload.readUnsignedShort();

            log.info("[UDP] {} -- {} --> {}:{}", sender, recipient, dstAddr, dstPort);

            /*-
             * FIXED #5760 Netty DNS Answer Section not correctly decoded
             * https://github.com/netty/netty/issues/5760
             */
            final ByteBuf payloadToUse = payload.copy();
            final InetSocketAddress recipientToReplace = SocketUtils.toSocketAddress(dstAddr, dstPort);
            out.add(new DatagramPacket(payloadToUse, recipientToReplace, sender));
        }
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
//            ownableServer.getNatMapChannel(rawSender, packetToUse.recipient(), ctx).sync().channel().writeAndFlush(packetToUse);
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

                                /*
                                final ByteBuf payloadToReplace = encode(rawPacket.content(), sender);

                                log.info("[UDP] {} -> {} -> {}: {}", sender, recipient, callback, ByteBufUtil.hexDump(payloadToReplace));

                                final DatagramPacket packet = new DatagramPacket(payloadToReplace, callback, sender);
                                */
//                                DatagramPacket packet = encode(new DatagramPacket(rawPacket.content(), callback, sender));
//                                callbackCtx.writeAndFlush(packet);
                                callbackCtx.writeAndFlush(new DatagramPacket(rawPacket.content().retain(), callback, sender));
                            }
                        });
                    }
                });
    }

}
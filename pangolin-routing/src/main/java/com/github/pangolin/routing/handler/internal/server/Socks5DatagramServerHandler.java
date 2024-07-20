package com.github.pangolin.routing.handler.internal.server;

import com.github.pangolin.routing.handler.internal.server.support.DatagramChannelFactory;
import com.github.pangolin.routing.handler.internal.server.support.StandardDatagramChannelFactory;
import com.github.pangolin.routing.util.SocketUtils;
import com.google.common.collect.Maps;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
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

import java.net.*;
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
        DatagramPacket packetToUse = packet.retain();
        /*-
         * UDP sync() is required.
         */
//        ownableServer.getNatMapChannel(rawSender, packetToUse.recipient(), ctx).sync().channel().writeAndFlush(packetToUse);
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


    /**
     * SOCKS5 server datagram packet codec.
     *
     * @see <a href="https://www.rfc-editor.org/rfc/rfc1928">SOCKS Protocol Version 5</a>
     */
    public class Socks5ServerDatagramPacketCodec extends MessageToMessageCodec<DatagramPacket, DatagramPacket> {
        private static final int RSV = 0x0000;
        private static final byte FRAG = 0x00;

        private final Socks5AddressEncoder addressEncoder;
        private final Socks5AddressDecoder addressDecoder;

        public Socks5ServerDatagramPacketCodec() {
            this(Socks5AddressEncoder.DEFAULT, Socks5AddressDecoder.DEFAULT);
        }

        public Socks5ServerDatagramPacketCodec(final Socks5AddressEncoder addressEncoder,
                                               final Socks5AddressDecoder addressDecoder) {
            this.addressEncoder = addressEncoder;
            this.addressDecoder = addressDecoder;
        }

        /**
         * {@inheritDoc}
         */
        @Override
        protected void encode(final ChannelHandlerContext ctx, final DatagramPacket packet, final List<Object> out) throws Exception {
            final InetSocketAddress sender = packet.sender();
            final ByteBuf rawPayload = packet.content();
            final InetSocketAddress recipient = packet.recipient();

            if (log.isDebugEnabled()) {
                log.debug("[SOCKS5/UDP] {} -> {}: {}", sender, recipient, ByteBufUtil.hexDump(rawPayload));
            }

            /*-
             +----+------+------+----------+----------+----------+
             |RSV | FRAG | ATYP | DST.ADDR | DST.PORT |   DATA   |
             +----+------+------+----------+----------+----------+
             | 2  |  1   |  1   | Variable |    2     | Variable |
             +----+------+------+----------+----------+----------+
             */
            final ByteBuf payloadToReplace = ctx.alloc().buffer(3 + 128 + rawPayload.readableBytes());
            payloadToReplace.writeShort(RSV);
            payloadToReplace.writeByte(FRAG);
            writeSocketAddress(payloadToReplace, sender, addressEncoder);
            payloadToReplace.writeBytes(rawPayload);

            out.add(packet.replace(payloadToReplace));
        }

        private void writeSocketAddress(final ByteBuf buf, final InetSocketAddress address,
                                        final Socks5AddressEncoder encoder) throws Exception {
            final InetAddress addr = address.getAddress();
            if (address.isUnresolved()) {
                buf.writeByte(Socks5AddressType.DOMAIN.byteValue());
                encoder.encodeAddress(Socks5AddressType.DOMAIN, address.getHostString(), buf);
            } else if (addr instanceof Inet4Address) {
                buf.writeByte(Socks5AddressType.IPv4.byteValue());
                encoder.encodeAddress(Socks5AddressType.IPv4, addr.getHostAddress(), buf);
            } else if (addr instanceof Inet6Address) {
                buf.writeByte(Socks5AddressType.IPv6.byteValue());
                encoder.encodeAddress(Socks5AddressType.IPv6, addr.getHostAddress(), buf);
            } else {
                throw new UnknownHostException(address.toString());
            }
            buf.writeShort(address.getPort());
        }

        @Override
        protected void decode(final ChannelHandlerContext ctx, final DatagramPacket packet, final List<Object> out) throws Exception {
            final InetSocketAddress sender = packet.sender();
            final InetSocketAddress recipient = packet.recipient();
            final ByteBuf rawPayload = packet.content();

            /*-
             +----+------+------+----------+----------+----------+
             |RSV | FRAG | ATYP | DST.ADDR | DST.PORT |   DATA   |
             +----+------+------+----------+----------+----------+
             | 2  |  1   |  1   | Variable |    2     | Variable |
             +----+------+------+----------+----------+----------+
             */

            // skip RSV (0x0000), FRAG (0x00)
            final int rsv = rawPayload.readUnsignedShort();
            final byte frag = rawPayload.readByte();

            final Socks5AddressType dstAddrType = Socks5AddressType.valueOf(rawPayload.readByte());
            final String dstAddr = addressDecoder.decodeAddress(dstAddrType, rawPayload);
            final int dstPort = rawPayload.readUnsignedShort();

            if (log.isDebugEnabled()) {
                log.debug("[SOCKS5/UDP] {} -> {} -> {}:{} : {}", sender, recipient, dstAddr, dstPort, ByteBufUtil.hexDump(rawPayload));
            }

            /*-
             * FIXED #5760 Netty DNS Answer Section not correctly decoded
             * https://github.com/netty/netty/issues/5760
             */
            final ByteBuf payloadToUse = rawPayload.copy();
            final InetSocketAddress recipientToReplace = SocketUtils.toSocketAddress(dstAddr, dstPort);
            out.add(new DatagramPacket(payloadToUse, recipientToReplace, sender));
        }
    }
}
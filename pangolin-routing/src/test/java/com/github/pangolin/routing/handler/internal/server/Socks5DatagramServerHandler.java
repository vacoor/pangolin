package com.github.pangolin.routing.handler.internal.server;

import com.github.pangolin.routing.handler.internal.server.support.DatagramChannelFactory;
import com.github.pangolin.routing.handler.internal.server.support.StandardDatagramChannelFactory;
import com.github.pangolin.server.NettyServer;
import com.google.common.collect.Maps;
import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.DatagramChannel;
import io.netty.channel.socket.DatagramPacket;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioDatagramChannel;
import io.netty.handler.codec.socksx.v5.Socks5AddressDecoder;
import io.netty.handler.codec.socksx.v5.Socks5AddressEncoder;
import io.netty.handler.codec.socksx.v5.Socks5AddressType;
import lombok.extern.slf4j.Slf4j;

import javax.net.ssl.SSLException;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.security.cert.CertificateException;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
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
            return new OwnedServer(a);
        });
    }

    void removeFromWhitelist(InetSocketAddress sender) {
        final OwnedServer ownedServer = natServers.remove(sender.getAddress());
        if (null != ownedServer) {
            log.info("Remove White: {}", sender);
            final Set<InetSocketAddress> natMapKeys = ownedServer.natMapKeys;
            for (InetSocketAddress natMapKey : natMapKeys) {
                natMap.get(natMapKey).channel().close();
            }
        }
    }

    @Override
    protected void channelRead0(final ChannelHandlerContext ctx, final DatagramPacket packet) throws Exception {
        final InetSocketAddress sender = packet.sender();
        final InetSocketAddress recipient = packet.recipient();
        final InetAddress owner = sender.getAddress();
        final OwnedServer ownedServer = natServers.get(owner);
//        final OwnedServer ownedServer = natServers.computeIfAbsent(owner, OwnedServer::new);
        if (null == ownedServer) {
            log.warn("SKIP sender: {} -> {}, {}", sender, recipient, natServers);
            return;
        }

        DatagramPacket packetToUse = decode(packet);
        ownedServer.getNatMapChannel(sender, ctx).channel().writeAndFlush(packetToUse);
    }

    private DatagramPacket decode(final DatagramPacket packet) throws Exception {
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
        final InetSocketAddress recipientToReplace = new InetSocketAddress(dstAddr, dstPort);
        return new DatagramPacket(payloadToUse, recipientToReplace, sender);
    }

    public ByteBuf encode(final ByteBuf rawPayload, final InetSocketAddress dest) throws Exception {
        final ByteBuf payloadToReplace = Unpooled.buffer(3 + rawPayload.readableBytes() + 128);
        final Socks5AddressEncoder encoder = Socks5AddressEncoder.DEFAULT;

        // RSV, FRAG
        payloadToReplace.writeShort(0);
        payloadToReplace.writeByte(0);
        if (dest.isUnresolved()) {
            payloadToReplace.writeByte(Socks5AddressType.DOMAIN.byteValue());
            encoder.encodeAddress(Socks5AddressType.DOMAIN, dest.getHostString(), payloadToReplace);
        } else {
            InetAddress sa = dest.getAddress();
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
        payloadToReplace.writeShort(dest.getPort());
        payloadToReplace.writeBytes(rawPayload.copy());

        return payloadToReplace;
    }

    private ConcurrentMap<InetSocketAddress, ChannelFuture> natMap = Maps.newConcurrentMap();
    private ConcurrentMap<InetAddress, OwnedServer> natServers = Maps.newConcurrentMap();

    private class OwnedServer {
        private final InetAddress owner;
        private final Set<InetSocketAddress> natMapKeys = Collections.newSetFromMap(
                new ConcurrentHashMap<>()
        );

        private OwnedServer(final InetAddress owner) {
            this.owner = owner;
        }

        public ChannelFuture getNatMapChannel(final InetSocketAddress sender, final ChannelHandlerContext context) {
            return natMap.computeIfAbsent(sender, key -> {
                natMapKeys.add(sender);
                return create(sender, context);
            });
        }

    }

    private ChannelFuture create(final InetSocketAddress callback, final ChannelHandlerContext callbackCtx) {
        return datagramChannelFactory.open(
                callbackCtx.channel().config().getConnectTimeoutMillis(),
                callbackCtx.channel().eventLoop(),
                new ChannelInitializer<DatagramChannel>() {
                    @Override
                    protected void initChannel(final DatagramChannel ch) throws Exception {
                        ch.pipeline().addLast(new SimpleChannelInboundHandler<DatagramPacket>() {
                            @Override
                            protected void channelRead0(final ChannelHandlerContext ctx, final DatagramPacket rawPacket) throws Exception {
                                final InetSocketAddress sender = rawPacket.sender();
                                final InetSocketAddress recipient = rawPacket.recipient();
                                log.info("[UDP] {} -> {} -> {}", sender, recipient, callback);

                                final ByteBuf payloadToReplace = encode(rawPacket.content(), sender);

                                // final DatagramPacket packet = new DatagramPacket(payload, callback);
                                final DatagramPacket packet = new DatagramPacket(payloadToReplace, callback, sender);
                                callbackCtx.writeAndFlush(packet);
                            }
                        });
                    }
                });
    }

    public static void main(String[] args) throws InterruptedException, CertificateException, SSLException {
        Socks5DatagramServerHandler udpServerHandler = new Socks5DatagramServerHandler();
        final Bootstrap udpBootstrap = new Bootstrap();
        udpBootstrap.group(new NioEventLoopGroup());
        udpBootstrap.channel(NioDatagramChannel.class);
        udpBootstrap.option(ChannelOption.SO_BROADCAST, false);
        udpBootstrap.handler(new ChannelInitializer<DatagramChannel>() {
            @Override
            protected void initChannel(final DatagramChannel ch) throws Exception {
                ch.pipeline().addLast(udpServerHandler);
            }
        });
//        udpBootstrap.bind("192.168.1.12", 1080).sync();
        udpBootstrap.bind("192.168.1.12", 1080);
        System.out.println("UDP");

        NettyServer server = new NettyServer("192.168.1.12", 1080);
        server.start(true, new ChannelInitializer<SocketChannel>() {
            @Override
            protected void initChannel(final SocketChannel ch) throws Exception {
                ch.pipeline().addLast(new Socks5ProxyServerHandler2(udpServerHandler));
            }
        }).channel().closeFuture().sync();
    }
}
package com.github.pangolin.routing.beta;

import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.DatagramChannel;
import io.netty.channel.socket.DatagramPacket;
import io.netty.channel.socket.nio.NioDatagramChannel;
import io.netty.handler.codec.socksx.v5.Socks5AddressDecoder;
import io.netty.handler.codec.socksx.v5.Socks5AddressEncoder;
import io.netty.handler.codec.socksx.v5.Socks5AddressType;
import lombok.extern.slf4j.Slf4j;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.concurrent.atomic.AtomicReference;

@Slf4j
public class Socks5UdpServerHandler extends SimpleChannelInboundHandler<DatagramPacket> {

    @Override
    protected void channelRead0(final ChannelHandlerContext ctx, final DatagramPacket packet) throws Exception {
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

        log.info("[UDP-F] {} -- {} --> {}:{}", sender, recipient, dstAddr, dstPort);

        // final DatagramPacket packetToUse = new DatagramPacket(payload.retain(), new InetSocketAddress(dstAddr, dstPort));
        final DatagramPacket packetToUse = new DatagramPacket(payload.retain(), new InetSocketAddress(dstAddr, dstPort), sender);

        get(sender, ctx, true).sync().channel().writeAndFlush(packetToUse);
    }

    //    private ConcurrentMap<InetSocketAddress, ChannelFuture> cache = Maps.newConcurrentMap();
    private final AtomicReference<ChannelFuture> ref = new AtomicReference<>();

    private ChannelFuture get(final InetSocketAddress sender, final ChannelHandlerContext callbackContext, final boolean create) {
//        return cache.computeIfAbsent(sender, key -> create ? forwarder(sender, callbackContext) : null);
        if (null == ref.get()) {
            ref.compareAndSet(null, forwarder(sender, callbackContext));
        }
        return ref.get();
    }

    private ChannelFuture forwarder(final InetSocketAddress callback, final ChannelHandlerContext callbackCtx) {
        final Bootstrap b = new Bootstrap();
        b.group(new NioEventLoopGroup());
        b.channel(NioDatagramChannel.class);
        b.option(ChannelOption.SO_BROADCAST, true);
        b.handler(new ChannelInitializer<DatagramChannel>() {
            @Override
            protected void initChannel(final DatagramChannel ch) throws Exception {
                ch.pipeline().addLast(new SimpleChannelInboundHandler<DatagramPacket>() {
                    @Override
                    protected void channelRead0(final ChannelHandlerContext channelHandlerContext, final DatagramPacket rawPacket) throws Exception {
                        final InetSocketAddress sender = rawPacket.sender();
                        final InetSocketAddress recipient = rawPacket.recipient();

                        final ByteBuf rawPayload = rawPacket.content().retain();
                        log.info("[UDP-C] {} -> {}: {}", sender, recipient, rawPayload);

                        final Socks5AddressEncoder encoder = Socks5AddressEncoder.DEFAULT;
                        final ByteBuf payload = Unpooled.buffer(3 + rawPayload.readableBytes() + 128);

                        // RSV, FRAG
                        payload.writeBytes(new byte[]{0, 0, 0});
                        if (sender.isUnresolved()) {
                            payload.writeByte(Socks5AddressType.DOMAIN.byteValue());
                            encoder.encodeAddress(Socks5AddressType.DOMAIN, sender.getHostString(), payload);
                        } else {
                            InetAddress sa = sender.getAddress();
                            if (sa instanceof Inet4Address) {
                                payload.writeByte(Socks5AddressType.IPv4.byteValue());
                                encoder.encodeAddress(Socks5AddressType.IPv4, sa.getHostAddress(), payload);
                            } else if (sa instanceof Inet6Address) {
                                payload.writeByte(Socks5AddressType.IPv6.byteValue());
                                encoder.encodeAddress(Socks5AddressType.IPv6, sa.getHostAddress(), payload);
                            } else {
                                throw new UnsupportedOperationException();
                            }
                        }
                        payload.writeShort(sender.getPort());
                        payload.writeBytes(rawPayload);

                        // final DatagramPacket packet = new DatagramPacket(payload, callback);
                        final DatagramPacket packet = new DatagramPacket(payload, callback, sender);
                        callbackCtx.writeAndFlush(packet);
                    }
                });
            }
        });
        return b.bind(0);
    }

    public static void main(String[] args) throws InterruptedException {
        final Bootstrap b = new Bootstrap();
        b.group(new NioEventLoopGroup());
        b.channel(NioDatagramChannel.class);
        b.option(ChannelOption.SO_BROADCAST, false);
        b.option(ChannelOption.SO_RCVBUF, 64 * 1024);// 设置UDP读缓冲区为64k
        b.option(ChannelOption.SO_SNDBUF, 64 * 1024);// 设置UDP写缓冲区为64k
        b.handler(new ChannelInitializer<DatagramChannel>() {
            @Override
            protected void initChannel(final DatagramChannel ch) throws Exception {
                ch.pipeline().addLast(new Socks5UdpServerHandler());
            }
        });
        b.bind(1080).sync();
    }
}
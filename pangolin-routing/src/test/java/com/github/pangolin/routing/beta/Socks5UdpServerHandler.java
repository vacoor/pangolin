package com.github.pangolin.routing.beta;

import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.DatagramChannel;
import io.netty.channel.socket.DatagramPacket;
import io.netty.channel.socket.nio.NioDatagramChannel;
import io.netty.handler.codec.socksx.v5.Socks5AddressDecoder;
import io.netty.handler.codec.socksx.v5.Socks5AddressEncoder;
import io.netty.handler.codec.socksx.v5.Socks5AddressType;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.GenericFutureListener;
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
           XXX check whitelist.
           getUpcRelayBySender
         */

        final ByteBuf payload = packet.content();

        /*-
         io.netty.handler.codec.socksx.v5.Socks5CommandRequestDecoder.decode
         */
        /*-
         +----+------+------+----------+----------+----------+
         |RSV | FRAG | ATYP | DST.ADDR | DST.PORT |   DATA   |
         +----+------+------+----------+----------+----------+
         | 2  |  1   |  1   | Variable |    2     | Variable |
         +----+------+------+----------+----------+----------+
         */
        // skip RSV (0x0000), FRAG (0x00)
        final int rsv = payload.readUnsignedShort();
        final byte frag = payload.readByte();

        final Socks5AddressType dstAddrType = Socks5AddressType.valueOf(payload.readByte());
        final String dstAddr = addressDecoder.decodeAddress(dstAddrType, payload);
        final int dstPort = payload.readUnsignedShort();

//        log.info("{} sender: {}/{} -> {}:{}", ctx.channel().id(), sender, recipient, dstAddr, dstPort);

        get(sender, ctx, true).sync().channel()
                .writeAndFlush(new DatagramPacket(payload.retain(), new InetSocketAddress(dstAddr, dstPort))).addListener(new GenericFutureListener<Future<? super Void>>() {
            @Override
            public void operationComplete(final Future<? super Void> future) throws Exception {
//                log.info("{} sender: {}/{} -> {}:{} -> {}", ctx.channel().id(), sender, recipient, dstAddr, dstPort, future.isSuccess());
            }
        });
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

    private ChannelFuture forwarder(final InetSocketAddress sender, final ChannelHandlerContext callbackContext) {
        final Bootstrap b = new Bootstrap();
        b.group(new NioEventLoopGroup());
        b.channel(NioDatagramChannel.class);
        b.option(ChannelOption.SO_BROADCAST, false);
//        b.option(ChannelOption.SO_BROADCAST, true);
        b.option(ChannelOption.SO_RCVBUF, 64 * 1024);// 设置UDP读缓冲区为64k
        b.option(ChannelOption.SO_SNDBUF, 64 * 1024);// 设置UDP写缓冲区为64k
        b.handler(new ChannelInitializer<DatagramChannel>() {
            @Override
            protected void initChannel(final DatagramChannel ch) throws Exception {
                ch.pipeline().addLast(new SimpleChannelInboundHandler<DatagramPacket>() {
                    @Override
                    public void channelActive(final ChannelHandlerContext ctx) throws Exception {
//                        log.info("send: {}:{}", ch.localAddress(), ch.remoteAddress());
                        super.channelActive(ctx);
                    }

                    @Override
                    protected void channelRead0(final ChannelHandlerContext channelHandlerContext, final DatagramPacket datagramPacket) throws Exception {
                        final InetSocketAddress reply = datagramPacket.sender();
                        InetSocketAddress recipient1 = datagramPacket.recipient();
                        log.info("replay: {} -> {}", reply, recipient1);
                        final ByteBuf payload = datagramPacket.content();
                        final Socks5AddressEncoder addressEncoder = Socks5AddressEncoder.DEFAULT;
                        ByteBuf buffer = Unpooled.buffer(3 + payload.readableBytes() + 128);
                        buffer.writeBytes(new byte[]{0, 0, 0});
                        if (reply.isUnresolved()) {
                            addressEncoder.encodeAddress(Socks5AddressType.DOMAIN, reply.getHostString(), buffer);
                        } else {
                            InetAddress sa = reply.getAddress();
                            if (sa instanceof Inet4Address) {
                                addressEncoder.encodeAddress(Socks5AddressType.IPv4, sa.getHostAddress(), buffer);
                            } else if (sa instanceof Inet6Address) {
                                addressEncoder.encodeAddress(Socks5AddressType.IPv6, sa.getHostAddress(), buffer);
                            } else {
                                throw new UnsupportedOperationException();
                            }
                        }
                        buffer.writeShort(reply.getPort());
                        buffer.writeBytes(payload);
//                        datagramPacket.content().retain();
                        callbackContext.writeAndFlush(new DatagramPacket(buffer, sender));
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
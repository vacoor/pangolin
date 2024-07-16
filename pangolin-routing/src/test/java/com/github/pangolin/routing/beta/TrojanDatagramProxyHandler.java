package com.github.pangolin.routing.beta;

import freework.codec.Hex;
import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.channel.*;
import io.netty.channel.socket.DatagramPacket;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.ByteToMessageDecoder;
import io.netty.handler.codec.MessageToByteEncoder;
import io.netty.handler.codec.socksx.v5.Socks5AddressDecoder;
import io.netty.handler.codec.socksx.v5.Socks5AddressEncoder;
import io.netty.handler.codec.socksx.v5.Socks5AddressType;
import io.netty.util.NetUtil;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.GenericFutureListener;

import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.List;

/**
 *
 */
public class TrojanDatagramProxyHandler extends ChannelDuplexHandler {
    private static final byte[] CRLF = {0x0D, 0x0A};
    private final InetSocketAddress proxyAddress;
    private final String proxyPassword;

    private volatile ChannelFuture udpOverTcp;

    public TrojanDatagramProxyHandler(final InetSocketAddress proxyAddress, final String proxyPassword) {
        this.proxyAddress = proxyAddress;
        this.proxyPassword = proxyPassword;
    }

    @Override
    public void write(final ChannelHandlerContext udpCtx, final Object msg, final ChannelPromise promise) throws Exception {
        udpOverTcp.channel().writeAndFlush(msg).addListener(new GenericFutureListener<Future<? super Void>>() {
            @Override
            public void operationComplete(final Future<? super Void> future) throws Exception {
                if (future.isSuccess()) {
                    promise.trySuccess();
                } else {
                    promise.tryFailure(future.cause());
                }
            }
        });
    }

    @Override
    public void bind(final ChannelHandlerContext udpCtx, final SocketAddress localAddress, final ChannelPromise promise) throws Exception {
        EventLoopGroup proxyGroup = udpCtx.channel().eventLoop();
        Bootstrap b = new Bootstrap();
        udpOverTcp = b.group(proxyGroup).channel(NioSocketChannel.class)
                .handler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        ch.pipeline().addLast(new TrojanProxyHandshakeHandler(proxyAddress, proxyPassword));
                        ch.pipeline().addLast(new UdpOverTcpEncoder());
                        ch.pipeline().addLast(new UdpOverTcpDecoder());
                        ch.pipeline().addLast(new SimpleChannelInboundHandler<DatagramPacket>() {
                            @Override
                            public void channelActive(final ChannelHandlerContext ctx) throws Exception {
                                TrojanDatagramProxyHandler.super.bind(udpCtx, localAddress, promise);
                            }

                            @Override
                            public void channelRead0(final ChannelHandlerContext ctx, final DatagramPacket msg) throws Exception {
                                System.out.println(msg);
                                // FIXME replace recipt
//                                udpCtx.writeAndFlush(msg.retain());
                                udpCtx.fireChannelRead(msg.retain());
                            }
                        });
                    }
                }).connect(proxyAddress);
    }

    public static class UdpOverTcpEncoder extends MessageToByteEncoder<DatagramPacket> {
        @Override
        protected void encode(final ChannelHandlerContext ctx, final DatagramPacket msg, final ByteBuf out) throws Exception {
            final InetSocketAddress sa = msg.recipient();
            final ByteBuf payload = msg.content();

//            ByteBuf buffer = Unpooled.buffer(3 + payload.readableBytes() + 128);

            if (sa.isUnresolved()) {
                out.writeByte(Socks5AddressType.DOMAIN.byteValue());
                Socks5AddressEncoder.DEFAULT.encodeAddress(Socks5AddressType.DOMAIN, sa.getHostString(), out);
            } else {
                final String host = sa.getAddress().getHostAddress();
                if (NetUtil.isValidIpV4Address(host)) {
                    out.writeByte(Socks5AddressType.IPv4.byteValue());
                    Socks5AddressEncoder.DEFAULT.encodeAddress(Socks5AddressType.IPv4, host, out);
                } else if (NetUtil.isValidIpV6Address(host)) {
                    out.writeByte(Socks5AddressType.IPv6.byteValue());
                    Socks5AddressEncoder.DEFAULT.encodeAddress(Socks5AddressType.IPv6, host, out);
                } else {
                    throw new ConnectException("unknown address type: " + sa.getClass().getName());
                }
            }
            out.writeShort(sa.getPort());
            out.writeShort(payload.readableBytes());
            out.writeBytes(CRLF);
            out.writeBytes(payload);
            System.out.println("ENCODE: " + Hex.encode(ByteBufUtil.getBytes(out)));
        }

    }

    public static class UdpOverTcpDecoder extends ByteToMessageDecoder {

        @Override
        protected void decode(final ChannelHandlerContext ctx, final ByteBuf in, final List<Object> out) throws Exception {
            out.add(decode(in, (InetSocketAddress) ctx.channel().localAddress()));
        }

        private DatagramPacket decode(final ByteBuf packet, final InetSocketAddress recipient) throws Exception {
            final Socks5AddressDecoder addressDecoder = Socks5AddressDecoder.DEFAULT;
            /*-
             +----+------+------+----------+----------+----------+
             |RSV | FRAG | ATYP | DST.ADDR | DST.PORT |   DATA   |
             +----+------+------+----------+----------+----------+
             | 2  |  1   |  1   | Variable |    2     | Variable |
             +----+------+------+----------+----------+----------+
             */
            final Socks5AddressType dstAddrType = Socks5AddressType.valueOf(packet.readByte());
            final String dstAddr = addressDecoder.decodeAddress(dstAddrType, packet);
            final int dstPort = packet.readUnsignedShort();

            final int length = packet.readUnsignedShort();
            packet.readBytes(2); // CRLF
            final ByteBuf rawPayload = packet.readBytes(length);

            /*-
             * FIXED #5760 Netty DNS Answer Section not correctly decoded
             * https://github.com/netty/netty/issues/5760
             */
            final InetSocketAddress sender = new InetSocketAddress(dstAddr, dstPort);
            return new DatagramPacket(rawPayload, recipient, sender);
        }
    }
}

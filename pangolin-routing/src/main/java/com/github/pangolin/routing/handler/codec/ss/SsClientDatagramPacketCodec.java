package com.github.pangolin.routing.handler.codec.ss;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.socket.DatagramPacket;
import io.netty.handler.codec.MessageToMessageCodec;
import io.netty.handler.codec.socksx.v5.Socks5AddressDecoder;
import io.netty.handler.codec.socksx.v5.Socks5AddressEncoder;
import io.netty.handler.codec.socksx.v5.Socks5AddressType;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.List;

public class SsClientDatagramPacketCodec extends MessageToMessageCodec<DatagramPacket, DatagramPacket> {
    private final InetSocketAddress proxyAddress;

    public SsClientDatagramPacketCodec(final InetSocketAddress proxyAddress) {
        this.proxyAddress = proxyAddress;
    }

    @Override
    protected void encode(final ChannelHandlerContext ctx, final DatagramPacket datagramPacket, final List<Object> out) throws Exception {
        final InetSocketAddress recipient = datagramPacket.recipient();

        final Socks5AddressEncoder encoder = Socks5AddressEncoder.DEFAULT;
        final ByteBuf buf = ctx.alloc().buffer(3 + datagramPacket.content().readableBytes() + 128);

        if (recipient.isUnresolved()) {
            buf.writeByte(Socks5AddressType.DOMAIN.byteValue());
            encoder.encodeAddress(Socks5AddressType.DOMAIN, recipient.getHostName(), buf);
        } else {
            final InetAddress address = recipient.getAddress();
            if (address instanceof Inet4Address) {
                buf.writeByte(Socks5AddressType.IPv4.byteValue());
                encoder.encodeAddress(Socks5AddressType.IPv4, address.getHostAddress(), buf);
            } else if (address instanceof Inet6Address) {
                buf.writeByte(Socks5AddressType.IPv6.byteValue());
                encoder.encodeAddress(Socks5AddressType.IPv6, address.getHostAddress(), buf);
            } else {
                throw new IllegalStateException();
            }
        }
        buf.writeShort(recipient.getPort());
        buf.writeBytes(datagramPacket.content());

        out.add(new DatagramPacket(buf, proxyAddress, datagramPacket.sender()));
    }

    @Override
    protected void decode(final ChannelHandlerContext ctx, final DatagramPacket in, final List<Object> out) throws Exception {
        final InetSocketAddress reply = in.sender();
        final ByteBuf payload = in.retain().content();
        final Socks5AddressDecoder decoder = Socks5AddressDecoder.DEFAULT;
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
//            final int rsv = payload.readUnsignedShort();
//            final byte frag = payload.readByte();

        final Socks5AddressType dstAddrType = Socks5AddressType.valueOf(payload.readByte());
        final String dstAddr = decoder.decodeAddress(dstAddrType, payload);
        final int dstPort = payload.readUnsignedShort();

        out.add(new DatagramPacket(payload, in.recipient(), new InetSocketAddress(dstAddr, dstPort)));
    }

}
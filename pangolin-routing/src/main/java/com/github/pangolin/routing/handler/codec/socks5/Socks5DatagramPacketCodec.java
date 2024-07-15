package com.github.pangolin.routing.handler.codec.socks5;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
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

/**
 * SOCKS5 Client
 * @see <a href="https://www.rfc-editor.org/rfc/rfc1928">SOCKS Protocol Version 5</a>
 */
@Slf4j
public class Socks5DatagramPacketCodec extends MessageToMessageCodec<DatagramPacket, DatagramPacket> {
    private final InetSocketAddress proxyAddress;

    public Socks5DatagramPacketCodec(final InetSocketAddress proxyAddress) {
        this.proxyAddress = proxyAddress;
    }

    @Override
    protected void encode(final ChannelHandlerContext channelHandlerContext, final DatagramPacket packet, final List<Object> list) throws Exception {
        final InetSocketAddress recipient = packet.recipient();

        ByteBuf payloadToReplace = encode(packet.content(), recipient);

        list.add(new DatagramPacket(payloadToReplace, proxyAddress, packet.sender()));
    }

    private ByteBuf encode(final ByteBuf rawPayload, final InetSocketAddress dest) throws Exception {
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

    @Override
    protected void decode(final ChannelHandlerContext channelHandlerContext, final DatagramPacket datagramPacket, final List<Object> list) throws Exception {
        final InetSocketAddress reply = datagramPacket.sender();

        DatagramPacket payloadToUse = decode(datagramPacket);

        list.add(payloadToUse);
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
        final InetSocketAddress senderToReplace = new InetSocketAddress(dstAddr, dstPort);
        return new DatagramPacket(payloadToUse, recipient, senderToReplace);
    }
}
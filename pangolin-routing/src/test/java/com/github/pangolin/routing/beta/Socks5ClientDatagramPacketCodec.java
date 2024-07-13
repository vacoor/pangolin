package com.github.pangolin.routing.beta;

import freework.codec.Hex;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
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
 * @see <a href="https://www.rfc-editor.org/rfc/rfc1928">SOCKS Protocol Version 5</a>
 */
@Slf4j
class Socks5ClientDatagramPacketCodec extends MessageToMessageCodec<DatagramPacket, DatagramPacket> {
    private final InetSocketAddress proxyAddress;

    Socks5ClientDatagramPacketCodec(final InetSocketAddress proxyAddress) {
        this.proxyAddress = proxyAddress;
    }

    @Override
    protected void encode(final ChannelHandlerContext channelHandlerContext, final DatagramPacket datagramPacket, final List<Object> list) throws Exception {
        final InetSocketAddress recipient = datagramPacket.recipient();

        final Socks5AddressEncoder encoder = Socks5AddressEncoder.DEFAULT;
        final ByteBuf buf = channelHandlerContext.alloc().buffer(3 + datagramPacket.content().readableBytes() + 128);

        // skip RSV (0x0000), FRAG (0x00)
        buf.writeBytes(new byte[]{0, 0, 0});

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

        list.add(new DatagramPacket(buf, proxyAddress, datagramPacket.sender()));
    }

    @Override
    protected void decode(final ChannelHandlerContext channelHandlerContext, final DatagramPacket datagramPacket, final List<Object> list) throws Exception {
        final InetSocketAddress reply = datagramPacket.sender();
        final ByteBuf payload = datagramPacket.content();

        log.info("[UDP-C2] {} -> {}: {}", reply, datagramPacket.recipient(), Hex.encode(ByteBufUtil.getBytes(payload.copy())));

        final Socks5AddressDecoder decoder = Socks5AddressDecoder.DEFAULT;
        //-
        // +----+------+------+----------+----------+----------+
        // |RSV | FRAG | ATYP | DST.ADDR | DST.PORT |   DATA   |
        // +----+------+------+----------+----------+----------+
        // | 2  |  1   |  1   | Variable |    2     | Variable |
        // +----+------+------+----------+----------+----------+
        //
        // skip RSV (0x0000), FRAG (0x00)
        final int rsv = payload.readUnsignedShort();
        final byte frag = payload.readByte();

        final Socks5AddressType dstAddrType = Socks5AddressType.valueOf(payload.readByte());
        final String dstAddr = decoder.decodeAddress(dstAddrType, payload);
        final int dstPort = payload.readUnsignedShort();

        log.info("[UDP-C] {} -> {}: {}", reply, datagramPacket.recipient(), Hex.encode(ByteBufUtil.getBytes(payload.copy())));

        /*-
         * FIXED #5760 Netty DNS Answer Section not correctly decoded
         * https://github.com/netty/netty/issues/5760
         */
        final ByteBuf payloadToUse = payload.copy();
        list.add(new DatagramPacket(payloadToUse, datagramPacket.recipient(), new InetSocketAddress(dstAddr, dstPort)));
    }

}
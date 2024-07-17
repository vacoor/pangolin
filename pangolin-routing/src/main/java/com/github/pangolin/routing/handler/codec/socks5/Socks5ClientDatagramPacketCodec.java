package com.github.pangolin.routing.handler.codec.socks5;

import com.github.pangolin.routing.util.SocketUtils;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.socket.DatagramPacket;
import io.netty.handler.codec.DecoderException;
import io.netty.handler.codec.MessageToMessageCodec;
import io.netty.handler.codec.socksx.v5.Socks5AddressDecoder;
import io.netty.handler.codec.socksx.v5.Socks5AddressEncoder;
import io.netty.handler.codec.socksx.v5.Socks5AddressType;
import lombok.extern.slf4j.Slf4j;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.util.List;

/**
 * SOCKS5 client datagram packet codec.
 *
 * @see <a href="https://www.rfc-editor.org/rfc/rfc1928">SOCKS Protocol Version 5</a>
 */
@Slf4j
public class Socks5ClientDatagramPacketCodec extends MessageToMessageCodec<DatagramPacket, DatagramPacket> {
    private static final int RSV = 0x0000;
    private static final byte FRAG = 0x00;

    private final InetSocketAddress proxyAddress;
    private final Socks5AddressEncoder addressEncoder;
    private final Socks5AddressDecoder addressDecoder;

    public Socks5ClientDatagramPacketCodec(final InetSocketAddress proxyAddress) {
        this(proxyAddress, Socks5AddressEncoder.DEFAULT, Socks5AddressDecoder.DEFAULT);
    }

    public Socks5ClientDatagramPacketCodec(final InetSocketAddress proxyAddress,
                                           final Socks5AddressEncoder addressEncoder,
                                           final Socks5AddressDecoder addressDecoder) {
        this.proxyAddress = proxyAddress;
        this.addressEncoder = addressEncoder;
        this.addressDecoder = addressDecoder;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void encode(final ChannelHandlerContext ctx, final DatagramPacket packet, final List<Object> out) throws Exception {
        final InetSocketAddress recipient = packet.recipient();
        final ByteBuf rawPayload = packet.content();

        log.info("[SOCKS5/UDP] {} -> {} -> {}: {}", ctx.channel().localAddress(), proxyAddress, recipient, ByteBufUtil.hexDump(rawPayload));

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
        writeSocketAddress(payloadToReplace, recipient, addressEncoder);
        payloadToReplace.writeBytes(rawPayload);

        log.info("[SOCKS5/UDP] {} -> {}: {}", ctx.channel().localAddress(), proxyAddress, ByteBufUtil.hexDump(payloadToReplace));

        out.add(new DatagramPacket(payloadToReplace, proxyAddress, packet.sender()));
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

    /**
     * {@inheritDoc}
     */
    @Override
    protected void decode(final ChannelHandlerContext ctx, final DatagramPacket packet, final List<Object> out) throws Exception {
        final InetSocketAddress sender = packet.sender();
        final InetSocketAddress recipient = packet.recipient();
        final ByteBuf rawPayload = packet.content();

        log.info("[SOCKS5/UDP] {} -> {}: {}", sender, recipient, ByteBufUtil.hexDump(rawPayload));

        /*-
         +----+------+------+----------+----------+----------+
         |RSV | FRAG | ATYP | DST.ADDR | DST.PORT |   DATA   |
         +----+------+------+----------+----------+----------+
         | 2  |  1   |  1   | Variable |    2     | Variable |
         +----+------+------+----------+----------+----------+
         */

        // skip RSV (0x0000), FRAG (0x00)
        assertEquals(RSV, rawPayload.readUnsignedShort(), "RSV");
        assertEquals(FRAG, rawPayload.readByte(), "multiple fragment not support, FRAG");

        final Socks5AddressType dstAddrType = Socks5AddressType.valueOf(rawPayload.readByte());
        final String dstAddr = addressDecoder.decodeAddress(dstAddrType, rawPayload);
        final int dstPort = rawPayload.readUnsignedShort();

        log.info("[SOCKS5/UDP] {}:{} -> {} -> {}: {}", dstAddr, dstPort, sender, recipient, ByteBufUtil.hexDump(rawPayload));

        /*-
         * FIXED #5760 Netty DNS Answer Section not correctly decoded
         * https://github.com/netty/netty/issues/5760
         */
        final ByteBuf payloadToUse = rawPayload.copy();
        final InetSocketAddress senderToReplace = SocketUtils.toSocketAddress(dstAddr, dstPort);
        out.add(new DatagramPacket(payloadToUse, recipient, senderToReplace));
    }

    private void assertEquals(final int expected, final int actual, final String name) {
        if (expected != actual) {
            throw new DecoderException(String.format("%s expected same:<%s> was not:<%s>", name, expected, actual));
        }
    }

}
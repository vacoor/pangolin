package com.github.pangolin.routing.handler.codec.ss;

import com.github.pangolin.routing.util.SocketUtils;
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
import java.net.UnknownHostException;
import java.util.List;

/**
 * @see <a href="https://shadowsocks.org/doc/what-is-shadowsocks.html">What is Shadowsocks?</a>
 * @see <a href="https://github.com/shadowsocks/shadowsocks-org/wiki/Protocol#udp">Protocol - UDP</a>
 */
@Slf4j
public class SsClientDatagramCodec extends MessageToMessageCodec<DatagramPacket, DatagramPacket> {
    private final InetSocketAddress proxyAddress;
    private final Socks5AddressEncoder addressEncoder;
    private final Socks5AddressDecoder addressDecoder;

    public SsClientDatagramCodec(final InetSocketAddress proxyAddress) {
        this(proxyAddress, Socks5AddressEncoder.DEFAULT, Socks5AddressDecoder.DEFAULT);
    }

    public SsClientDatagramCodec(final InetSocketAddress proxyAddress,
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

        log.info("[SS/UDP] {} -> {} -> {}: {}", ctx.channel().localAddress(), proxyAddress, recipient, ByteBufUtil.hexDump(rawPayload));

        /*-
         +------+----------+----------+----------+
         | ATYP | DST.ADDR | DST.PORT |   DATA   |
         +------+----------+----------+----------+
         |  1   | Variable |    2     | Variable |
         +------+----------+----------+----------+
         */
        final ByteBuf payloadToReplace = ctx.alloc().buffer(128 + rawPayload.readableBytes());
        writeSocketAddress(payloadToReplace, recipient, addressEncoder);
        payloadToReplace.writeBytes(rawPayload);

        log.info("[SS/UDP] {} -> {}: {}", ctx.channel().localAddress(), proxyAddress, ByteBufUtil.hexDump(payloadToReplace));

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

        log.info("[SS/UDP] {} -> {}: {}", sender, recipient, ByteBufUtil.hexDump(rawPayload));

        /*-
         +------+----------+----------+----------+
         | ATYP | DST.ADDR | DST.PORT |   DATA   |
         +------+----------+----------+----------+
         |  1   | Variable |    2     | Variable |
         +------+----------+----------+----------+
         */

        final Socks5AddressType dstAddrType = Socks5AddressType.valueOf(rawPayload.readByte());
        final String dstAddr = addressDecoder.decodeAddress(dstAddrType, rawPayload);
        final int dstPort = rawPayload.readUnsignedShort();

        log.info("[SS/UDP] {}:{} -> {} -> {}: {}", dstAddr, dstPort, sender, recipient, ByteBufUtil.hexDump(rawPayload));

        /*-
         * FIXED #5760 Netty DNS Answer Section not correctly decoded
         * https://github.com/netty/netty/issues/5760
         */
        final ByteBuf payloadToUse = rawPayload.copy();
        final InetSocketAddress senderToReplace = SocketUtils.toSocketAddress(dstAddr, dstPort);
        out.add(new DatagramPacket(payloadToUse, recipient, senderToReplace));
    }

}
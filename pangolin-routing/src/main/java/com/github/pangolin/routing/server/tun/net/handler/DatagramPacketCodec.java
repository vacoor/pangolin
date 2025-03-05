package com.github.pangolin.routing.server.tun.net.handler;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.socket.DatagramPacket;
import io.netty.handler.codec.MessageToMessageCodec;
import org.pcap4j.packet.IpPacket;
import org.pcap4j.packet.IpV4Packet;
import org.pcap4j.packet.UdpPacket;
import org.pcap4j.packet.UnknownPacket;
import org.pcap4j.packet.namednumber.IpNumber;
import org.pcap4j.packet.namednumber.IpV4TosTos;
import org.pcap4j.packet.namednumber.IpVersion;
import org.pcap4j.packet.namednumber.UdpPort;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.util.List;

/**
 */
public class DatagramPacketCodec extends MessageToMessageCodec<IpPacket, DatagramPacket> {

    @Override
    protected void encode(final ChannelHandlerContext ctx, final DatagramPacket msg, final List<Object> out) throws Exception {
        final InetSocketAddress sender = msg.sender();
        final InetSocketAddress recipient = msg.recipient();
        final int srcPort = sender.getPort();
        final int dstPort = recipient.getPort();
        final InetAddress srcAddr = sender.getAddress();
        final InetAddress dstAddr = recipient.getAddress();
        final ByteBuf content = msg.content();
        byte[] bytes = ByteBufUtil.getBytes(content);

        final UdpPacket payload = new UdpPacket.Builder()
                .srcAddr(srcAddr)
                .srcPort(UdpPort.getInstance((short) srcPort))
                .dstAddr(dstAddr)
                .dstPort(UdpPort.getInstance((short) dstPort))
                .payloadBuilder(UnknownPacket.newPacket(bytes, 0, bytes.length).getBuilder())
                .correctLengthAtBuild(true)
                .correctChecksumAtBuild(true)
                .build();
        if (srcAddr instanceof Inet4Address) {

        }
    }

    @Override
    public boolean acceptInboundMessage(final Object msg) throws Exception {
        if (!super.acceptInboundMessage(msg)) {
            return false;
        }
        IpPacket ipPacket = (IpPacket) msg;
        IpPacket.IpHeader ih = ipPacket.getHeader();
        return IpNumber.UDP.equals(ih.getProtocol());
    }

    @Override
    protected void decode(final ChannelHandlerContext ctx, final IpPacket msg, final List<Object> out) throws Exception {
        final UdpPacket payload = (UdpPacket) msg.getPayload();
        final InetAddress srcAddr = msg.getHeader().getSrcAddr();
        final InetAddress dstAddr = msg.getHeader().getDstAddr();
        final UdpPort srcPort = payload.getHeader().getSrcPort();
        final UdpPort dstPort = payload.getHeader().getDstPort();

            final InetSocketAddress sender = new InetSocketAddress(srcAddr, srcPort.valueAsInt());
            final InetSocketAddress recipient = new InetSocketAddress(dstAddr, dstPort.valueAsInt());
            final byte[] rawData = payload.getPayload().getRawData();
            final ByteBuf data = Unpooled.wrappedBuffer(rawData);

    }

    public static void main(String[] args) throws UnknownHostException {
        InetAddress srcAddr = InetAddress.getByName("192.168.1.1");
        InetAddress dstAddr = InetAddress.getByName("192.168.1.2");
        IpV4Packet build = new IpV4Packet.Builder()
                .version(IpVersion.IPV4)
                .protocol(IpNumber.UDP)
                .srcAddr((Inet4Address) srcAddr)
                .dstAddr((Inet4Address) dstAddr)
                .ttl((byte) 10)
                .paddingAtBuild(true)
                .correctLengthAtBuild(true)
                .correctChecksumAtBuild(true)
                .build();
        System.out.println();
    }
}

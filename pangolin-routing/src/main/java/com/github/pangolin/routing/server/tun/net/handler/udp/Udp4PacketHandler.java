package com.github.pangolin.routing.server.tun.net.handler.udp;

import com.github.pangolin.routing.server.tun.net.handler.IpPacketHandler;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.socket.DatagramPacket;
import org.pcap4j.packet.IpV4Packet;
import org.pcap4j.packet.UdpPacket;
import org.pcap4j.packet.namednumber.IpNumber;
import org.pcap4j.packet.namednumber.UdpPort;

import java.net.Inet4Address;
import java.net.InetSocketAddress;

/**
 *
 */
public class Udp4PacketHandler extends IpPacketHandler<IpV4Packet> {

    public Udp4PacketHandler() {
        super(IpNumber.UDP);
    }

    @Override
    protected void channelRead0(final ChannelHandlerContext ctx, final IpV4Packet msg) throws Exception {
        final UdpPacket payload = (UdpPacket) msg.getPayload();
        final Inet4Address srcAddr = msg.getHeader().getSrcAddr();
        final Inet4Address dstAddr = msg.getHeader().getDstAddr();
        final UdpPort srcPort = payload.getHeader().getSrcPort();
        final UdpPort dstPort = payload.getHeader().getDstPort();

        if (UdpPort.DOMAIN.equals(dstPort) && "198.18.0.254".equals(dstAddr.getHostAddress())) {
            final InetSocketAddress sender = new InetSocketAddress(srcAddr, srcPort.valueAsInt());
            final InetSocketAddress recipient = new InetSocketAddress(dstAddr, dstPort.valueAsInt());
            final byte[] rawData = payload.getPayload().getRawData();
            final ByteBuf data = Unpooled.wrappedBuffer(rawData);

            ctx.fireChannelRead(new DatagramPacket(data, recipient, sender));
        }
    }


}

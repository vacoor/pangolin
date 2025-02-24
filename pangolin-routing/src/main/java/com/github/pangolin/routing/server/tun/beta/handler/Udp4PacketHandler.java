package com.github.pangolin.routing.server.tun.beta.handler;

import io.netty.channel.ChannelHandlerContext;
import org.pcap4j.packet.IpV4Packet;
import org.pcap4j.packet.Packet;
import org.pcap4j.packet.UdpPacket;
import org.pcap4j.packet.namednumber.IpNumber;
import org.pcap4j.packet.namednumber.UdpPort;

import java.net.Inet4Address;

/**
 */
public class Udp4PacketHandler extends IpPacketHandler<IpV4Packet> {

    public Udp4PacketHandler() {
        super(IpNumber.UDP);
    }

    @Override
    protected void channelRead0(final ChannelHandlerContext ctx, final IpV4Packet msg) throws Exception {
        Inet4Address dstAddr = msg.getHeader().getDstAddr();
        final UdpPacket payload = (UdpPacket) msg.getPayload();
        final UdpPort dstPort = payload.getHeader().getDstPort();
        if (UdpPort.DOMAIN.equals(dstPort) && "198.18.0.254".equals(dstAddr.getHostAddress())) {
            // if dst addr == 198.18.0.254
            // reply dns query
            System.out.println("DNS");
        }
    }
}

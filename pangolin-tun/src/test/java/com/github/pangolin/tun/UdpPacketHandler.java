package com.github.pangolin.tun;

import io.netty.channel.ChannelHandlerContext;
import org.pcap4j.packet.IpPacket;
import org.pcap4j.packet.UdpPacket;
import org.pcap4j.packet.namednumber.IpNumber;
import org.pcap4j.packet.namednumber.UdpPort;

import java.net.InetAddress;

public class UdpPacketHandler extends IpPacketHandler {

    public UdpPacketHandler() {
        super(IpNumber.UDP);
    }

    @Override
    protected void channelRead0(final ChannelHandlerContext ctx, final IpPacket ipPacket) throws Exception {
        final IpPacket.IpHeader ipHeader = ipPacket.getHeader();
        final InetAddress srcAddr = ipHeader.getSrcAddr();
        final InetAddress dstAddr = ipHeader.getDstAddr();
        final IpNumber protocol = ipHeader.getProtocol();

        final UdpPacket udpPacket = (UdpPacket) ipPacket.getPayload();
        final UdpPort dstPort = udpPacket.getHeader().getDstPort();
        if (dstPort.valueAsInt() == 5353) {
            return;
        }
    }

}

package com.github.pangolin.routing.server.tun.net.handler;

import io.netty.channel.ChannelHandlerContext;
import org.pcap4j.packet.IpPacket;
import org.pcap4j.packet.IpV4Packet;
import org.pcap4j.packet.Packet;
import org.pcap4j.packet.namednumber.IpNumber;

/**
 */
public class IcmpV4PacketHandler extends IpPacketHandler<IpV4Packet> {
    public IcmpV4PacketHandler() {
        super(IpNumber.ICMPV4);
    }

    @Override
    protected void channelRead0(final ChannelHandlerContext ctx, final IpV4Packet msg) throws Exception {
        Packet payload = msg.getPayload();
        System.out.println();
    }
}

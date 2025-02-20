package com.github.pangolin.routing.server.tun.beta.handler;

import io.netty.channel.SimpleChannelInboundHandler;
import org.pcap4j.packet.IpPacket;
import org.pcap4j.packet.namednumber.IpNumber;

public abstract class IpPacketHandler<T extends IpPacket> extends SimpleChannelInboundHandler<T> {
    private final IpNumber inboundProtocol;

    public IpPacketHandler(final IpNumber inboundProtocol) {
        this.inboundProtocol = inboundProtocol;
    }

    @Override
    public boolean acceptInboundMessage(final Object msg) throws Exception {
        if (!super.acceptInboundMessage(msg)) {
            return false;
        }

        final IpPacket ipPacket = (IpPacket) msg;
        final IpPacket.IpHeader ipHeader = ipPacket.getHeader();
        final IpNumber protocol = ipHeader.getProtocol();
        return this.inboundProtocol.equals(protocol);
    }

}

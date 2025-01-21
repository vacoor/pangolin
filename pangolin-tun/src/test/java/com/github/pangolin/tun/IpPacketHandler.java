package com.github.pangolin.tun;

import com.google.common.collect.Maps;
import io.netty.channel.SimpleChannelInboundHandler;
import org.pcap4j.packet.IpPacket;
import org.pcap4j.packet.namednumber.IpNumber;

import java.util.Map;

public abstract class IpPacketHandler extends SimpleChannelInboundHandler<IpPacket> {
    private final IpNumber inboundProtocol;
    private final Map<String, TcpSession> sessionMap = Maps.newConcurrentMap();

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

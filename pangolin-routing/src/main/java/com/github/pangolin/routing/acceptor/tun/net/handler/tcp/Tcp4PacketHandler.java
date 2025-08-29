package com.github.pangolin.routing.acceptor.tun.net.handler.tcp;

import com.github.pangolin.routing.acceptor.tun.net.handler.tcp.internal.Tcp4Connection;
import com.github.pangolin.routing.acceptor.tun.net.handler.tcp.internal.TcpConnection;
import com.github.pangolin.routing.support.SocketChannelFactory;
import com.github.pangolin.routing.acceptor.tun.fakedns.DnsEngine;
import io.netty.channel.Channel;
import io.netty.channel.EventLoopGroup;
import java.net.Inet4Address;
import java.net.UnknownHostException;
import lombok.extern.slf4j.Slf4j;
import org.pcap4j.packet.IpV4Packet;
import org.pcap4j.packet.IpV4Packet.IpV4Header;
import org.pcap4j.packet.IpV4Rfc791Tos;
import org.pcap4j.packet.Packet;
import org.pcap4j.packet.TcpPacket;
import org.pcap4j.packet.TcpPacket.Builder;
import org.pcap4j.packet.TcpPacket.TcpHeader;

@Slf4j
public class Tcp4PacketHandler extends TcpPacketHandler<IpV4Packet> {

    public Tcp4PacketHandler(final DnsEngine dnsEngine, final SocketChannelFactory factory) {
        super(dnsEngine, factory);
    }

    @Override
    protected IpV4Packet newReset(IpV4Packet ipPacket) {
        final IpV4Header ih = ipPacket.getHeader();
        final TcpPacket tcpPacket = ipPacket.get(TcpPacket.class);
        final TcpHeader th = tcpPacket.getHeader();
        final TcpPacket.Builder rstPacket = new TcpPacket.Builder()
            .srcAddr(ih.getDstAddr())
            .srcPort(th.getDstPort())
            .dstAddr(ih.getSrcAddr())
            .dstPort(th.getSrcPort())
            .sequenceNumber(0)
            .ack(true)
            .acknowledgmentNumber(th.getSequenceNumber())
            .rst(true)
            .paddingAtBuild(true)
            .correctChecksumAtBuild(true)
            .correctLengthAtBuild(true);

        return new IpV4Packet.Builder()
            .protocol(ih.getProtocol())
            .version(ih.getVersion())
            .tos(ih.getTos())
            .srcAddr(ih.getDstAddr())
            .dstAddr(ih.getSrcAddr())
            .payloadBuilder(rstPacket)
            .paddingAtBuild(true)
            .correctLengthAtBuild(true)
            .correctChecksumAtBuild(true)
            .build();
    }

    @Override
    protected IpV4Packet prepare(IpV4Packet ipPacket) throws UnknownHostException {
        final Inet4Address dstAddr = ipPacket.getHeader().getDstAddr();
        return ipPacket.getBuilder().dstAddr((Inet4Address) resolveDstAddress(dstAddr)).build();
    }

    @Override
    protected TcpConnection<IpV4Packet> create(final Channel parent, final EventLoopGroup childGroup, final DnsEngine dnsEngine, final SocketChannelFactory socketChannelFactory, final Runnable destroyCallback) {
        return new Tcp4Connection(parent, childGroup, dnsEngine, socketChannelFactory) {
            @Override
            protected void destroy0() {
                destroyCallback.run();
            }
        };
    }

}

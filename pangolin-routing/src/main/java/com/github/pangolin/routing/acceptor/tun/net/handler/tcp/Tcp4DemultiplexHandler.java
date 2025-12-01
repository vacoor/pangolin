package com.github.pangolin.routing.acceptor.tun.net.handler.tcp;

import com.github.pangolin.routing.acceptor.tun.fakedns.DnsEngine;
import com.github.pangolin.routing.acceptor.tun.net.handler.tcp.core.Tcp4Demultiplexer;
import com.github.pangolin.routing.acceptor.tun.net.handler.tcp.core.TcpDemultiplexer;
import com.github.pangolin.routing.acceptor.tun.net.handler.tcp.internal.TcpSock;
import com.github.pangolin.routing.acceptor.tun.net.handler.tcp.internal.tcp_request_sock;
import com.github.pangolin.routing.support.SocketChannelFactory;
import io.netty.channel.EventLoopGroup;
import lombok.extern.slf4j.Slf4j;
import org.pcap4j.packet.IpV4Packet;

import java.net.Inet4Address;
import java.net.UnknownHostException;
import java.util.Map;

@Slf4j
public class Tcp4DemultiplexHandler extends TcpDemultiplexHandler<IpV4Packet> {

    public Tcp4DemultiplexHandler(final DnsEngine dnsEngine, final SocketChannelFactory factory) {
        super(dnsEngine, factory);
    }

    @Override
    protected IpV4Packet prepare(final IpV4Packet ipPacket) throws UnknownHostException {
        final Inet4Address dstAddr = ipPacket.getHeader().getDstAddr();
        return ipPacket.getBuilder().dstAddr((Inet4Address) resolveDstAddress(dstAddr)).build();
//        return ipPacket;
    }

    @Override
    protected TcpDemultiplexer<IpV4Packet> create(
            final Map<String, tcp_request_sock> synRegistry,
            final Map<String, TcpSock> establishedRegistry,
            final EventLoopGroup childGroup, final DnsEngine dnsEngine,
            final SocketChannelFactory socketChannelFactory) {
        return new Tcp4Demultiplexer(synRegistry, establishedRegistry, childGroup, dnsEngine, socketChannelFactory);
    }

}

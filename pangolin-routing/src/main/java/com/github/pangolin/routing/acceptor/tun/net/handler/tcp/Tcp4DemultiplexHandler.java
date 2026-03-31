package com.github.pangolin.routing.acceptor.tun.net.handler.tcp;

import com.github.pangolin.routing.acceptor.tun.fakedns.DnsEngine;
import com.github.pangolin.routing.acceptor.tun.net.handler.support.TcpPacketBuf;
import com.github.pangolin.routing.acceptor.tun.net.handler.tcp.core.Tcp4Demultiplexer;
import com.github.pangolin.routing.acceptor.tun.net.handler.tcp.core.TcpDemultiplexer;
import com.github.pangolin.routing.acceptor.tun.net.handler.tcp.internal.TcpSock;
import com.github.pangolin.routing.acceptor.tun.net.handler.tcp.internal.tcp_request_sock;
import com.github.pangolin.routing.support.SocketChannelFactory;
import io.netty.channel.EventLoopGroup;
import lombok.extern.slf4j.Slf4j;

import java.net.UnknownHostException;
import java.util.Map;

@Slf4j
public class Tcp4DemultiplexHandler extends TcpDemultiplexHandler {

    public Tcp4DemultiplexHandler(final DnsEngine dnsEngine, final SocketChannelFactory factory) {
        super(dnsEngine, factory);
    }

    /**
     * DNS-resolve the destination address and store it as metadata on the packet.
     * The buf is never modified — O(1) operation.
     */
    @Override
    protected TcpPacketBuf prepare(final TcpPacketBuf pkt) throws UnknownHostException {
        pkt.resolvedDstAddr(resolveDstAddress(pkt.dstAddrBytes()));
        return pkt;
    }

    @Override
    protected TcpDemultiplexer create(
            final Map<String, tcp_request_sock> synRegistry,
            final Map<String, TcpSock> establishedRegistry,
            final EventLoopGroup childGroup, final DnsEngine dnsEngine,
            final SocketChannelFactory socketChannelFactory) {
        return new Tcp4Demultiplexer(synRegistry, establishedRegistry, childGroup, dnsEngine, socketChannelFactory);
    }
}

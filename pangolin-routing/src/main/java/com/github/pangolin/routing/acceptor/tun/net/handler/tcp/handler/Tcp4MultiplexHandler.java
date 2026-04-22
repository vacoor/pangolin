package com.github.pangolin.routing.acceptor.tun.net.handler.tcp.handler;

import com.github.pangolin.routing.acceptor.tun.fakedns.DnsEngine;
import com.github.pangolin.routing.acceptor.tun.net.codec.TcpPacketBuf;
import com.github.pangolin.routing.acceptor.tun.net.handler.tcp.core.Tcp4Multiplexer;
import com.github.pangolin.routing.acceptor.tun.net.handler.tcp.core.TcpMultiplexer;
import com.github.pangolin.routing.acceptor.tun.net.handler.tcp.sock.TcpSock;
import com.github.pangolin.routing.acceptor.tun.net.handler.tcp.sock.tcp_request_sock;
import com.github.pangolin.routing.support.SocketChannelFactory;
import io.netty.channel.EventLoopGroup;
import lombok.extern.slf4j.Slf4j;

import java.net.UnknownHostException;
import java.util.Map;

@Slf4j
public class Tcp4MultiplexHandler extends TcpMultiplexHandler {

    public Tcp4MultiplexHandler(final DnsEngine dnsEngine, final SocketChannelFactory factory) {
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
    protected TcpMultiplexer create(
            final Map<String, tcp_request_sock> synRegistry,
            final Map<String, TcpSock> establishedRegistry,
            final EventLoopGroup childGroup, final DnsEngine dnsEngine,
            final SocketChannelFactory socketChannelFactory) {
        return new Tcp4Multiplexer(synRegistry, establishedRegistry, childGroup, dnsEngine, socketChannelFactory);
    }
}

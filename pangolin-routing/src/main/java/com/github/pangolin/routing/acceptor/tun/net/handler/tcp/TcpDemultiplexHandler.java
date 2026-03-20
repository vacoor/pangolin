package com.github.pangolin.routing.acceptor.tun.net.handler.tcp;

import com.github.pangolin.routing.acceptor.tun.fakedns.DnsEngine;
import com.github.pangolin.routing.acceptor.tun.net.handler.support.IpPacketHandler;
import com.github.pangolin.routing.acceptor.tun.net.handler.support.TcpPacketBuf;
import com.github.pangolin.routing.acceptor.tun.net.handler.tcp.core.TcpDemultiplexer;
import com.github.pangolin.routing.acceptor.tun.net.handler.tcp.internal.TcpSock;
import com.github.pangolin.routing.acceptor.tun.net.handler.tcp.internal.tcp_request_sock;
import com.github.pangolin.routing.support.SocketChannelFactory;
import com.google.common.collect.Maps;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import lombok.extern.slf4j.Slf4j;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Map;

@Slf4j
public abstract class TcpDemultiplexHandler extends IpPacketHandler<TcpPacketBuf> {

    private static final byte PROTO_TCP = 6;

    private final DnsEngine dnsEngine;
    private final EventLoopGroup childGroup = new NioEventLoopGroup();

    private final Map<String, tcp_request_sock> synRegistry = Maps.newConcurrentMap();
    private final Map<String, TcpSock> establishedRegistry = Maps.newConcurrentMap();
    private final TcpDemultiplexer demultiplexer;

    public TcpDemultiplexHandler(final DnsEngine dnsEngine, final SocketChannelFactory factory) {
        super(PROTO_TCP);
        this.dnsEngine = dnsEngine;
        this.demultiplexer = create(synRegistry, establishedRegistry, childGroup, dnsEngine, factory);
    }

    @Override
    protected void channelRead0(final ChannelHandlerContext ctx, final TcpPacketBuf rawPkt) throws Exception {
        try {
            final TcpPacketBuf pkt = prepare(rawPkt);
            demultiplexer.tcp_rcv(ctx.channel(), pkt);
        } catch (final Exception ex) {
            log.error("Failed to process TCP packet", ex);
            demultiplexer.send_reset(ctx.channel(), rawPkt, -77);
        }
    }

    protected TcpPacketBuf prepare(final TcpPacketBuf pkt) throws UnknownHostException {
        return pkt;
    }

    protected InetAddress resolveDstAddress(final InetAddress address) throws UnknownHostException {
        final byte[] addr = address.getAddress();
        if (dnsEngine.isFakeAddress(addr)) {
            final String hostname = dnsEngine.getHostByAddress(addr);
            if (null != hostname && !hostname.isEmpty()) {
                return InetAddress.getByAddress(hostname, addr);
            }
        }
        return noDnsQuery(address);
    }

    protected InetAddress noDnsQuery(final InetAddress address) throws UnknownHostException {
        return InetAddress.getByAddress(address.getHostAddress(), address.getAddress());
    }

    protected abstract TcpDemultiplexer create(
            Map<String, tcp_request_sock> synRegistry,
            Map<String, TcpSock> establishedRegistry,
            EventLoopGroup childGroup,
            DnsEngine dnsEngine, SocketChannelFactory socketChannelFactory);
}

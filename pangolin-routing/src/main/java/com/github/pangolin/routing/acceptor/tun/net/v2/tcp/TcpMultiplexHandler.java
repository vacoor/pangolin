package com.github.pangolin.routing.acceptor.tun.net.v2.tcp;

import com.github.pangolin.routing.acceptor.tun.fakedns.DnsEngine;
import com.github.pangolin.routing.acceptor.tun.net.handler.support.IpPacketHandler;
import com.github.pangolin.routing.acceptor.tun.net.handler.support.TcpPacketBuf;
import com.github.pangolin.routing.acceptor.tun.net.handler.tcp.core.Tcp4Multiplexer;
import com.github.pangolin.routing.acceptor.tun.net.handler.tcp.core.TcpMultiplexer;
import com.github.pangolin.routing.acceptor.tun.net.handler.tcp.sock.TcpSock;
import com.github.pangolin.routing.acceptor.tun.net.handler.tcp.sock.tcp_request_sock;
import com.github.pangolin.routing.support.SocketChannelFactory;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.EventLoopGroup;
import lombok.extern.slf4j.Slf4j;

import java.net.UnknownHostException;
import java.util.Map;

/**
 * v2 TCP ingress now follows v1 architecture:
 * TCP logic is handled by {@link TcpMultiplexer}, not Netty pipeline handlers.
 */
@Slf4j
public class TcpMultiplexHandler extends IpPacketHandler<TcpPacketBuf> {

    private static final byte PROTO_TCP = 6;

    private final DnsEngine dnsEngine;
    private final TcpMultiplexer multiplexer;

    public TcpMultiplexHandler(final DnsEngine dnsEngine,
                               final SocketChannelFactory socketChannelFactory) {
        super(PROTO_TCP);
        this.dnsEngine = dnsEngine;
        this.multiplexer = create(dnsEngine, socketChannelFactory);
    }

    @Override
    protected void channelRead0(final ChannelHandlerContext ctx, final TcpPacketBuf rawPkt) {
        try {
            final TcpPacketBuf pkt = prepare(rawPkt);
            multiplexer.tcp_rcv(ctx.channel(), pkt);
        } catch (final Exception ex) {
            log.error("Failed to process TCP packet", ex);
            multiplexer.send_reset(ctx.channel(), rawPkt, -77);
        }
    }

    protected TcpPacketBuf prepare(final TcpPacketBuf pkt) throws UnknownHostException {
        pkt.resolvedDstAddr(resolveDstAddress(pkt.dstAddrBytes()));
        return pkt;
    }

    protected TcpMultiplexer create(final DnsEngine dnsEngine,
                                    final SocketChannelFactory socketChannelFactory) {
        final Map<String, tcp_request_sock> synRegistry = new java.util.concurrent.ConcurrentHashMap<>();
        final Map<String, TcpSock> establishedRegistry = new java.util.concurrent.ConcurrentHashMap<>();
        final EventLoopGroup childGroup = new io.netty.channel.nio.NioEventLoopGroup();
        return new Tcp4Multiplexer(synRegistry, establishedRegistry, childGroup, dnsEngine, socketChannelFactory);
    }

    protected java.net.InetAddress resolveDstAddress(final byte[] addr) throws UnknownHostException {
        if (dnsEngine.isFakeAddress(addr)) {
            final String hostname = dnsEngine.getHostByAddress(addr);
            if (hostname != null && !hostname.isEmpty()) {
                return java.net.InetAddress.getByAddress(hostname, addr);
            }
        }
        return java.net.InetAddress.getByAddress(io.netty.util.NetUtil.bytesToIpAddress(addr), addr);
    }
}

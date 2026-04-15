package com.github.pangolin.routing.acceptor.tun.net.v2.tcp;

import com.github.pangolin.routing.acceptor.tun.fakedns.DnsEngine;
import com.github.pangolin.routing.acceptor.tun.net.handler.support.IpPacketHandler;
import com.github.pangolin.routing.acceptor.tun.net.handler.support.TcpPacketBuf;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.internal.TcpConfig;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.ng.TcpMultiplexer;
import io.netty.channel.ChannelHandlerContext;
import lombok.extern.slf4j.Slf4j;

import java.net.UnknownHostException;

/**
 * v2 TCP ingress now follows v1 architecture:
 * TCP logic is handled by {@link TcpMultiplexer}, not Netty pipeline handlers.
 */
@Slf4j
public class TcpMultiplexHandler extends IpPacketHandler<TcpPacketBuf> {

    private static final byte PROTO_TCP = 6;

    private final DnsEngine dnsEngine;
    private final TcpMultiplexer multiplexer;

    public TcpMultiplexHandler(final DnsEngine dnsEngine) {
        super(PROTO_TCP);
        this.dnsEngine = dnsEngine;
        this.multiplexer = create();
    }

    @Override
    protected void channelRead0(final ChannelHandlerContext ctx, final TcpPacketBuf rawPkt) {
        try {
            final TcpPacketBuf pkt = prepare(rawPkt);
            multiplexer.tcp_rcv(ctx, pkt);
        } catch (final Exception ex) {
            log.error("Failed to process TCP packet", ex);
            // fallback reset in ingress layer
            com.github.pangolin.routing.acceptor.tun.net.v2.tcp.core.TcpOutput.INSTANCE.tcp_v4_send_reset(ctx, rawPkt);
        }
    }

    protected TcpPacketBuf prepare(final TcpPacketBuf pkt) throws UnknownHostException {
        pkt.resolvedDstAddr(resolveDstAddress(pkt.dstAddrBytes()));
        return pkt;
    }

    protected TcpMultiplexer create() {
        return new TcpMultiplexer(TcpConfig.builder().build());
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

package com.github.pangolin.routing.acceptor.tun.net.v2.tcp;

import com.github.pangolin.routing.acceptor.tun.fakedns.DnsEngine;
import com.github.pangolin.routing.acceptor.tun.net.codec.IpPacketHandler;
import com.github.pangolin.routing.acceptor.tun.net.codec.TcpPacketBuf;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.core.*;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.core.hook.TcpSockInitializer;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.UnknownHostException;

/**
 * v2 TCP ingress now follows v1 architecture:
 * TCP logic is handled by {@link SegmentDispatcher}, not Netty pipeline handlers.
 */
public class TcpMultiplexHandler extends IpPacketHandler<TcpPacketBuf> {

    private static final Logger log = LoggerFactory.getLogger(TcpMultiplexHandler.class);
    private static final byte PROTO_TCP = 6;

    private final DnsEngine dnsEngine;
    private final SegmentDispatcher multiplexer;


    public TcpMultiplexHandler(final DnsEngine dnsEngine,
                               final TcpSockInitializer initializer) {
        super(PROTO_TCP);
        this.dnsEngine = dnsEngine;
        this.multiplexer = create(initializer);
    }

    @Override
    protected void channelRead0(final ChannelHandlerContext ctx, final TcpPacketBuf rawPkt) {
        try {
            final TcpPacketBuf pkt = prepare(rawPkt);
            multiplexer.consume(ctx, pkt);
        } catch (final Exception ex) {
            log.error("Failed to process TCP packet", ex);
            // fallback reset in ingress layer
            multiplexer.output().v4SendReset(ctx, rawPkt);
        }
    }

    protected TcpPacketBuf prepare(final TcpPacketBuf pkt) throws UnknownHostException {
        pkt.resolvedDstAddr(resolveDstAddress(pkt.dstAddrBytes()));
        return pkt;
    }

    protected SegmentDispatcher create(final TcpSockInitializer initializer) {
        return new Ipv4SegmentDispatcher(TcpConfig.builder().build(), initializer);
    }

    public boolean write(final FourTuple key, final ByteBuf data) {
        return multiplexer.write(key, data);
    }

    public SegmentDispatcher multiplexer() {
        return multiplexer;
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

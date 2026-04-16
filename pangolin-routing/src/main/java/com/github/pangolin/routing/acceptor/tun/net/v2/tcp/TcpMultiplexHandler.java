package com.github.pangolin.routing.acceptor.tun.net.v2.tcp;

import com.github.pangolin.routing.acceptor.tun.fakedns.DnsEngine;
import com.github.pangolin.routing.acceptor.tun.net.handler.support.IpPacketHandler;
import com.github.pangolin.routing.acceptor.tun.net.handler.support.TcpPacketBuf;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.internal.FourTuple;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.internal.TcpConfig;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.ng.Tcp4Multiplexer;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.ng.TcpMultiplexer;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.ng.TcpMultiplexer.DataConsumer;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.UnknownHostException;

/**
 * v2 TCP ingress now follows v1 architecture:
 * TCP logic is handled by {@link TcpMultiplexer}, not Netty pipeline handlers.
 */
public class TcpMultiplexHandler extends IpPacketHandler<TcpPacketBuf> {

    private static final Logger log = LoggerFactory.getLogger(TcpMultiplexHandler.class);
    private static final byte PROTO_TCP = 6;

    private final DnsEngine dnsEngine;
    private final DataConsumer dataConsumer;
    private final TcpMultiplexer multiplexer;

    public TcpMultiplexHandler(final DnsEngine dnsEngine) {
        this(dnsEngine, null);
    }

    public TcpMultiplexHandler(final DnsEngine dnsEngine, final DataConsumer dataConsumer) {
        super(PROTO_TCP);
        this.dnsEngine = dnsEngine;
        this.dataConsumer = dataConsumer;
        this.multiplexer = create();
    }

    @Override
    protected void channelRead0(final ChannelHandlerContext ctx, final TcpPacketBuf rawPkt) {
        try {
            final TcpPacketBuf pkt = prepare(rawPkt);
            multiplexer.consume(ctx, pkt);
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
        return new Tcp4Multiplexer(TcpConfig.builder().build(), dataConsumer);
    }

    public boolean write(final FourTuple key, final ByteBuf data) {
        return multiplexer.write(key, data);
    }

    public TcpMultiplexer multiplexer() {
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

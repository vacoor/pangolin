package com.github.pangolin.routing.server.tun.net.handler;

import com.github.pangolin.routing.support.SocketChannelFactory;
import com.github.pangolin.routing.server.fakedns.DnsEngine;
import com.github.pangolin.routing.server.tun.net.TcpConnection;
import com.google.common.base.Preconditions;
import com.google.common.collect.Maps;
import freework.reflect.Types;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import lombok.extern.slf4j.Slf4j;
import org.pcap4j.packet.IpPacket;
import org.pcap4j.packet.IpPacket.IpHeader;
import org.pcap4j.packet.TcpPacket;
import org.pcap4j.packet.TcpPacket.TcpHeader;
import org.pcap4j.packet.namednumber.IpNumber;
import org.pcap4j.packet.namednumber.TcpPort;

import java.lang.reflect.Type;
import java.net.InetAddress;
import java.util.Map;

@Slf4j
public abstract class TcpPacketHandler<P extends IpPacket> extends IpPacketHandler<P> {
    private final Class<P> ipPacketType;
    private final DnsEngine dnsEngine;
    private final SocketChannelFactory socketChannelFactory;
    private final EventLoopGroup childGroup = new NioEventLoopGroup();

    private final Map<String, TcpConnection> sessionMap = Maps.newConcurrentMap();

    public TcpPacketHandler(final DnsEngine dnsEngine, final SocketChannelFactory factory) {
        super(IpNumber.TCP);
        this.dnsEngine = dnsEngine;
        this.socketChannelFactory = factory;
        final Type type = Types.resolveType(TcpPacketHandler.class.getTypeParameters()[0], getClass());
        Preconditions.checkState(type instanceof Class<?>, "Can't resolve %s IpPacket Class", TcpPacketHandler.class.getName());
        this.ipPacketType = (Class<P>) type;
    }

    @Override
    public boolean acceptInboundMessage(final Object msg) throws Exception {
        return super.acceptInboundMessage(msg) && ipPacketType.isInstance(msg);
    }

    @Override
    protected void channelRead0(final ChannelHandlerContext ctx, final P ipPacket) throws Exception {
        final IpHeader ipHeader = ipPacket.getHeader();
        final InetAddress srcAddr = ipHeader.getSrcAddr();
        final InetAddress dstAddr = ipHeader.getDstAddr();

        final TcpPacket tcpPacket = (TcpPacket) ipPacket.getPayload();
        final TcpHeader tcpHeader = tcpPacket.getHeader();
        final TcpPort tcpSrcPort = tcpHeader.getSrcPort();
        final TcpPort tcpDstPort = tcpHeader.getDstPort();

        final String sockKey = srcAddr.toString() + ":" + tcpSrcPort + " => " + dstAddr + ":" + tcpDstPort;
        if (!tcpHeader.getRst() && !tcpHeader.getAck() && tcpHeader.getSyn()) {
            sessionMap.putIfAbsent(sockKey, create(ctx.channel(), childGroup, dnsEngine, socketChannelFactory, () -> {
                    log.info("Destroy: {}", sockKey);
                    sessionMap.remove(sockKey);
            }));
        }
        TcpConnection<P> tcpConnection = sessionMap.get(sockKey);
        if (null != tcpConnection) {
            tcpConnection.handler(ipPacket, tcpPacket);
        }
    }

    protected abstract TcpConnection<P> create(Channel parent, EventLoopGroup childGroup,
                                            DnsEngine dnsEngine, SocketChannelFactory socketChannelFactory,
                                            Runnable destroyCallback);
}

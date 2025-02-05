package com.github.pangolin.routing.server.tun.beta.handler;

import com.github.pangolin.routing.handler.internal.server.support.SocketChannelFactory;
import com.github.pangolin.routing.server.fakedns.DnsEngine;
import com.github.pangolin.routing.server.tun.beta.TcpConnection;
import com.github.pangolin.routing.server.tun.beta.TcpConnection2;
import com.google.common.collect.Maps;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import lombok.extern.slf4j.Slf4j;
import org.pcap4j.packet.IpPacket;
import org.pcap4j.packet.IpPacket.IpHeader;
import org.pcap4j.packet.IpV4Packet;
import org.pcap4j.packet.TcpPacket;
import org.pcap4j.packet.TcpPacket.TcpHeader;
import org.pcap4j.packet.namednumber.IpNumber;
import org.pcap4j.packet.namednumber.IpVersion;
import org.pcap4j.packet.namednumber.TcpPort;

import java.net.InetAddress;
import java.util.Map;

@Slf4j
public class TcpPacketHandler2 extends IpPacketHandler {
    private final DnsEngine dnsEngine;
    private final SocketChannelFactory socketChannelFactory;
    private final EventLoopGroup childGroup = new NioEventLoopGroup();

    private final Map<String, TcpConnection2> sessionMap = Maps.newConcurrentMap();

    public TcpPacketHandler2(final DnsEngine dnsEngine, final SocketChannelFactory factory) {
        super(IpNumber.TCP);
        this.dnsEngine = dnsEngine;
        this.socketChannelFactory = factory;
    }

    @Override
    protected void channelRead0(final ChannelHandlerContext ctx, final IpPacket ipPacket) throws Exception {
        final IpHeader ipHeader = ipPacket.getHeader();
        final InetAddress srcAddr = ipHeader.getSrcAddr();
        final InetAddress dstAddr = ipHeader.getDstAddr();

        final TcpPacket tcpPacket = (TcpPacket) ipPacket.getPayload();
        final TcpHeader tcpHeader = tcpPacket.getHeader();
        final TcpPort tcpSrcPort = tcpHeader.getSrcPort();
        final TcpPort tcpDstPort = tcpHeader.getDstPort();

        final String sockKey = srcAddr.toString() + ":" + tcpSrcPort + " => " + dstAddr + ":" + tcpDstPort;
        if (!tcpHeader.getRst() && !tcpHeader.getAck() && tcpHeader.getSyn()) {
            sessionMap.putIfAbsent(sockKey, new TcpConnection2(ctx.channel(), childGroup, dnsEngine, socketChannelFactory) {
                @Override
                protected void onDestroy() {
                    log.info("Destroy: {}", sockKey);
                    sessionMap.remove(sockKey);
                }
            });
        }
        TcpConnection2 tcpConnection = sessionMap.get(sockKey);
        if (null != tcpConnection) {
            tcpConnection.receive(ipHeader, tcpPacket);
        } else {
            final TcpPacket.Builder builder = new TcpPacket.Builder();
            builder.srcAddr(dstAddr)
                    .dstAddr(srcAddr)
                    .srcPort(tcpDstPort)
                    .dstPort(tcpSrcPort)
                    .ack(true)
                    .rst(true)
                    .paddingAtBuild(true)
                    .correctLengthAtBuild(true)
                    .correctChecksumAtBuild(true);
            final IpV4Packet ipPacket0 = new IpV4Packet.Builder()
                    .version(IpVersion.IPV4)
                    .tos(((IpV4Packet.IpV4Header) ipHeader).getTos())
                    .ttl(((IpV4Packet.IpV4Header) ipHeader).getTtl())
                    .identification(((IpV4Packet.IpV4Header) ipHeader).getIdentification())
                    .fragmentOffset(((IpV4Packet.IpV4Header) ipHeader).getFragmentOffset())
                    .srcAddr(((IpV4Packet.IpV4Header) ipHeader).getDstAddr())
                    .dstAddr(((IpV4Packet.IpV4Header) ipHeader).getSrcAddr())
                    .protocol(IpNumber.TCP)

                    .paddingAtBuild(true)
                    .correctLengthAtBuild(true)
                    .correctChecksumAtBuild(true)

                    .payloadBuilder(builder)
                    .build();
            ctx.writeAndFlush(ipPacket0);
            // RST
//            throw new IllegalStateException();
        }
    }

}

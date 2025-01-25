package com.github.pangolin.routing.server.tun.beta.handler;

import com.github.pangolin.routing.handler.internal.server.support.SocketChannelFactory;
import com.github.pangolin.routing.server.fakedns.DnsEngine;
import com.github.pangolin.routing.server.tun.beta.TcpConnection;
import com.google.common.collect.Maps;
import io.netty.channel.ChannelHandlerContext;
import lombok.extern.slf4j.Slf4j;
import org.pcap4j.packet.IpPacket;
import org.pcap4j.packet.TcpPacket;
import org.pcap4j.packet.namednumber.IpNumber;
import org.pcap4j.packet.namednumber.TcpPort;

import java.net.InetAddress;
import java.util.Map;

@Slf4j
public class TcpPacketHandler extends IpPacketHandler {
    private final DnsEngine dnsEngine;
    private final SocketChannelFactory socketChannelFactory;

    private final Map<String, TcpConnection> sessionMap = Maps.newConcurrentMap();

    public TcpPacketHandler(final DnsEngine dnsEngine, final SocketChannelFactory factory) {
        super(IpNumber.TCP);
        this.dnsEngine = dnsEngine;
        this.socketChannelFactory = factory;
    }

    @Override
    protected void channelRead0(final ChannelHandlerContext ctx, final IpPacket ipPacket) throws Exception {
        final IpPacket.IpHeader ipHeader = ipPacket.getHeader();
        final InetAddress srcAddr = ipHeader.getSrcAddr();
        final InetAddress dstAddr = ipHeader.getDstAddr();

        final TcpPacket tcpPacket = (TcpPacket) ipPacket.getPayload();
        final TcpPacket.TcpHeader tcpHeader = tcpPacket.getHeader();
        final TcpPort tcpSrcPort = tcpHeader.getSrcPort();
        final TcpPort tcpDstPort = tcpHeader.getDstPort();

        final String sockKey = srcAddr.toString() + tcpSrcPort + dstAddr + tcpDstPort;
        if (!tcpHeader.getRst() && !tcpHeader.getAck() && tcpHeader.getSyn()) {
            sessionMap.putIfAbsent(sockKey, new TcpConnection(ctx.channel(), dnsEngine, socketChannelFactory) {
                @Override
                protected void onDestroy() {
                    log.info("Destroy: {}", sockKey);
                    sessionMap.remove(sockKey);
                }
            });
        }
        TcpConnection tcpConnection = sessionMap.get(sockKey);
        if (null != tcpConnection) {
            tcpConnection.receive(ipHeader, tcpPacket);
        } else {
            // RST
//            throw new IllegalStateException();
        }
    }

}

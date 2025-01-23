package com.github.pangolin.tun.beta.handler;

import com.github.pangolin.tun.beta.TcpSession;
import com.google.common.collect.Maps;
import io.netty.channel.ChannelHandlerContext;
import org.pcap4j.packet.IpPacket;
import org.pcap4j.packet.TcpPacket;
import org.pcap4j.packet.namednumber.IpNumber;
import org.pcap4j.packet.namednumber.TcpPort;

import java.net.InetAddress;
import java.util.Map;

public class TcpPacketHandler extends IpPacketHandler {
    private final Map<String, TcpSession> sessionMap = Maps.newConcurrentMap();

    public TcpPacketHandler() {
        super(IpNumber.TCP);
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
        if (!tcpHeader.getAck() && tcpHeader.getSyn()) {
            sessionMap.putIfAbsent(sockKey, new TcpSession(ctx) {
                @Override
                protected void onClosed() {
                    sessionMap.remove(sockKey);
                }
            });
        }
        TcpSession tcpSession = sessionMap.get(sockKey);
        if (null != tcpSession) {
            tcpSession.receive(tcpPacket, ipHeader);
        } else {
            // RST
            throw new IllegalStateException();
        }
    }

}

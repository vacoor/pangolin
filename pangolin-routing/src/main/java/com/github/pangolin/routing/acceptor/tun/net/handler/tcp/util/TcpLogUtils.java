package com.github.pangolin.routing.acceptor.tun.net.handler.tcp.util;

import org.pcap4j.packet.IpPacket;
import org.pcap4j.packet.TcpPacket;

import java.net.InetAddress;

public class TcpLogUtils {

    public static String logFormat(final IpPacket ipPacket, final Object... extra) {
        final StringBuilder buf = new StringBuilder();
        stringify(buf, ipPacket);
        for (final Object o : extra) {
            buf.append(o);
        }
        return buf.toString();
    }

    public static StringBuilder stringify(final StringBuilder buf, final IpPacket ipPacket) {
        final IpPacket.IpHeader iph = ipPacket.getHeader();
        final TcpPacket tp = ipPacket.get(TcpPacket.class);
        final TcpPacket.TcpHeader th = null != tp ? tp.getHeader() : null;

        buf.append(iph.getProtocol().name()).append(" ");
        stringify(buf, iph.getSrcAddr());
        if (null != th) {
            buf.append(":").append(th.getSrcPort().valueAsInt());
        }

        buf.append(" -> ");

        stringify(buf, iph.getDstAddr());
        if (null != th) {
            buf.append(":").append(th.getDstPort().valueAsInt());
        }

        return buf.append(" ");
    }


    private static StringBuilder stringify(final StringBuilder buff, final InetAddress addr) {
        final String hostname = addr.getHostName();
        final String hostAddress = addr.getHostAddress();
        if (null != hostname && !hostname.isEmpty() && !hostname.equals(hostAddress)) {
            buff.append(hostname).append("/");
        }
        return buff.append(hostAddress);
    }

}

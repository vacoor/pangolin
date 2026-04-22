package com.github.pangolin.routing.acceptor.tun.net.handler.tcp.util;

import com.github.pangolin.routing.acceptor.tun.net.codec.TcpPacketBuf;

import java.net.InetAddress;

public class TcpLogUtils {

    public static String logify(final TcpPacketBuf pkt, final int wscale) {
        final StringBuilder buff = new StringBuilder();
        stringify(buff, pkt);

        final int len = buff.length();
        if (pkt.isFin()) buff.append("FIN,");
        if (pkt.isSyn()) buff.append("SYN,");
        if (pkt.isRst()) buff.append("RST,");
        if (pkt.isPsh()) buff.append("PSH,");
        if (pkt.isAck()) buff.append("ACK,");
        if (pkt.isUrg()) buff.append("URG,");

        if (buff.length() > len) {
            buff.replace(buff.length() - 1, buff.length(), "] ").insert(len, "[");
        }

        long sequence = pkt.tcpSeq() & 0xFFFFFFFFL;
        long acknowledgment = pkt.tcpAckNum() & 0xFFFFFFFFL;

        buff.append("Seq=").append(sequence);
        if (pkt.isAck()) {
            buff.append(" Ack=").append(acknowledgment);
        }

        final int window = pkt.tcpWindow() << wscale;
        buff.append(" Win=").append(window);

        final int payloadLen = pkt.tcpPayloadLength();
        buff.append(" Len=").append(payloadLen);

        return buff.toString();
    }

    public static String logFormat(final String protocol, final TcpPacketBuf pkt, final Object... extra) {
        return logFormat(protocol, pkt.srcAddr(), pkt.tcpSrcPort(), pkt.dstAddr(), pkt.tcpDstPort(), extra);
        /*
        final StringBuilder buf = new StringBuilder();
        stringify(buf, pkt);
        for (final Object o : extra) {
            buf.append(o);
        }
        return buf.toString();
         */
    }

    public static String logFormat(final String protocol,
                                   final InetAddress srcAddr, final int srcPort,
                                   final InetAddress dstAddr, final int dstPort,
                                   final Object... extra) {
        final StringBuilder buf = new StringBuilder();
        stringify(buf, protocol, srcAddr, srcPort, dstAddr, dstPort);
        for (final Object o : extra) {
            buf.append(o);
        }
        return buf.toString();
    }

    public static StringBuilder stringify(final StringBuilder buf, final TcpPacketBuf pkt) {
        return stringify(
                buf, "[TCP]",
                pkt.srcAddr(), pkt.tcpSrcPort(),
                pkt.dstAddr(), pkt.tcpDstPort()
        );
    }

    private static StringBuilder stringify(final StringBuilder buf,
                                           final String protocol,
                                           final InetAddress srcAddr, final int srcPort,
                                           final InetAddress dstAddr, final int dstPort) {
        buf.append(protocol).append(" ");
        stringify(buf, srcAddr);
        if (srcPort > 0) {
            buf.append(":").append(srcPort);
        }

        buf.append(" -> ");

        stringify(buf, dstAddr);
        if (dstPort > 0) {
            buf.append(":").append(dstPort);
        }
        return buf.append(" ");
    }

    private static StringBuilder stringify(final StringBuilder buff, final InetAddress addr) {
        String hostname = TcpLogUtils.getHostNameNoResolve(addr);
        hostname = null != hostname ? hostname : addr.getHostAddress();
        final String hostAddress = addr.getHostAddress();
        if (null != hostname && !hostname.isEmpty() && !hostname.equals(hostAddress)) {
            buff.append(hostname).append("/");
        }
        return buff.append(hostAddress);
    }

    public static String getHostNameNoResolve(final InetAddress address) {
        /*-
         * FIXME: A reverse name lookup will be performed and the result will be returned
         * based on the system configured name lookup service.
         */
        final String hostnameAndAddress = address.toString();
        final int index = hostnameAndAddress.indexOf("/");
        return 0 < index ? hostnameAndAddress.substring(0, index) : null;
    }

}

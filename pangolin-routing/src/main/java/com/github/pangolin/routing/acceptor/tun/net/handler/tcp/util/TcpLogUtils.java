package com.github.pangolin.routing.acceptor.tun.net.handler.tcp.util;

import org.pcap4j.packet.IpPacket;
import org.pcap4j.packet.TcpPacket;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.InetAddress;

public class TcpLogUtils {

    public static String logify(final IpPacket ipPacket, final int wscale) {
        final StringBuilder buff = new StringBuilder();
        stringify(buff, ipPacket);

        final TcpPacket tp = ipPacket.get(TcpPacket.class);
        if (null == tp) {
            return buff.toString();
        }

        final TcpPacket.TcpHeader th = tp.getHeader();
        final int len = buff.length();
        if (th.getFin()) {
            buff.append("FIN,");
        }
        if (th.getSyn()) {
            buff.append("SYN,");
        }
        if (th.getRst()) {
            buff.append("RST,");
        }
        if (th.getPsh()) {
            buff.append("PSH,");
        }
        if (th.getAck()) {
            buff.append("ACK,");
        }
        if (th.getUrg()) {
            buff.append("URG,");
        }

        if (buff.length() > len) {
            buff.replace(buff.length() - 1, buff.length(), "] ").insert(len, "[");
        }

        final boolean useRelative = false;
        long sequence = th.getSequenceNumberAsLong();
        long acknowledgment = th.getAcknowledgmentNumberAsLong();

        /*
        if (useRelative) {
            final long rcv_isn_l = rcv_isn & 0xFFFFFFFFL;
            final long snt_isn_l = snt_isn & 0xFFFFFFFFL;
            final boolean syn = tcpHeader.getSyn();
            sequence -= !syn ? rcv_isn_l : sequence;
            acknowledgment -= !syn ? snt_isn_l : acknowledgment - 1;
        }
        */

        buff.append("Seq=").append(sequence);
        if (th.getAck()) {
            buff.append(" Ack=").append(acknowledgment);
        }

        final int window = th.getWindowAsInt() << wscale;
        buff.append(" Win=").append(window);

        final int payloadLen = tp.length() - th.length();
        buff.append(" Len=").append(payloadLen);

        if (th.getSyn()) {

        }

        /*
        final Packet payload = tcpPacket.getPayload();
        if (null != payload) {
            buff.append(" ").append(Bytes.toString(payload.getRawData()));
        }
        */
        return buff.toString();
    }

    public static String logFormat(final IpPacket ipPacket, final Object... extra) {
        final StringBuilder buf = new StringBuilder();
        stringify(buf, ipPacket);
        for (final Object o : extra) {
            buf.append(o);
        }
        return buf.toString();
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

    public static StringBuilder stringify(final StringBuilder buf, final IpPacket ipPacket) {
        final IpPacket.IpHeader iph = ipPacket.getHeader();
        final TcpPacket tp = ipPacket.get(TcpPacket.class);
        final TcpPacket.TcpHeader th = null != tp ? tp.getHeader() : null;
        return stringify(
                buf, iph.getProtocol().name(),
                iph.getSrcAddr(), null != th ? th.getSrcPort().valueAsInt() : 0,
                iph.getDstAddr(), null != th ? th.getDstPort().valueAsInt() : 0
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
        return InetAddressHostNameGetter.getHostNameNoResolve(address);
    }

    private static class InetAddressHostNameGetter {
        private static final Method GET_HOLDER;
        private static final Method GET_HOLDER_HOST_NAME;

        static {
            try {
                final Method holder = InetAddress.class.getDeclaredMethod("holder");
                holder.setAccessible(true);

                final Class<?> inetAddressHolder = Class.forName(InetAddress.class.getName() + "$InetAddressHolder");
                final Method getHostName = inetAddressHolder.getDeclaredMethod("getHostName");
                getHostName.setAccessible(true);

                GET_HOLDER = holder;
                GET_HOLDER_HOST_NAME = getHostName;
            } catch (final NoSuchMethodException e) {
                throw new IllegalStateException(e);
            } catch (ClassNotFoundException e) {
                throw new IllegalStateException(e);
            }
        }

        public static String getHostNameNoResolve(final InetAddress address) {
            if (null == GET_HOLDER || null == GET_HOLDER_HOST_NAME) {
                return null;
            }

            try {
                return (String) GET_HOLDER_HOST_NAME.invoke(GET_HOLDER.invoke(address));
            } catch (final InvocationTargetException e) {
                throw new IllegalStateException(e);
            } catch (final IllegalAccessException e) {
                throw new IllegalStateException(e);
            }
        }
    }

}

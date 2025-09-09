package com.github.pangolin.routing.acceptor.tun.net.handler.tcp.internal;

import java.net.InetAddress;
import org.pcap4j.packet.IpPacket.IpHeader;
import org.pcap4j.packet.Packet;
import org.pcap4j.packet.TcpPacket;

import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.concurrent.atomic.AtomicLong;
import org.pcap4j.packet.TcpPacket.TcpHeader;

/**
 *
 */
public abstract class TcpUtils {
    private static final AtomicLong TIME_COUNTER = new AtomicLong(System.nanoTime());

    private TcpUtils() {
    }


    public static boolean before(final int seq1, final int seq2) {
        /*-
         * eg: Integer.MAX_VALUE - (Integer.MAX_VALUE + 1) => -1 < 0
         */
        return seq1 - seq2 < 0;
    }

    public static boolean after(final int seq2, final int seq1) {
        return before(seq1, seq2);
    }

    public static boolean between(final int seq1, final int seq2, final int seq3) {
        return seq3 - seq2 >= seq1 - seq2;
    }

    public static int align(final int len, final int align) {
        return (((len) + ((align) - 1)) & ~((align) - 1));
    }

    static boolean time_after(long a, long b) {
        return (b - a) < 0;
    }

    static boolean time_after_eq(long a, long b) {
        return (a - b) >= 0;
    }

    static boolean time_before(long a, long b) {
        return time_after(b, a);
    }

    static boolean time_before_eq(long a, long b) {
        return time_after_eq(b, a);
    }

    static long toUint32(final int value) {
        return value & 0xFFFFFFFFL;
    }

    static int ilog2(int a) {
        return (int) (Math.log(a) / Math.log(2));
    }


    static int _ilog2(int x) {
        int i = 0;
        while (x >= 2) {
            x = x >> 1;  // 右移一位相当于除以2
            i++;
        }
        return i;
    }


    static int clamp(int value, int min, int max) {
        if (value < min) {
            return min;
        }
        if (value > max) {
            return max;
        }
        return value;
    }

    /**
     * @param skb
     * @return
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_ipv4.c">tcp_ipv4.c</a>
     */
    public static int determineEndSeq(final TcpPacket skb) {
        final TcpPacket.TcpHeader hdr = skb.getHeader();
        int endSeq = hdr.getSequenceNumber();
        if (hdr.getSyn()) {
            endSeq++;
        }
        if (hdr.getFin()) {
            endSeq++;
        }
        return endSeq + skb.length() - hdr.length();
    }

    static int determineEndSeq(final TcpBuffer skb) {
        int endSeq = skb.sequenceNumber();
        if (skb.syn()) {
            endSeq++;
        }
        if (skb.fin()) {
            endSeq++;
        }
        final Packet.Builder b = skb.payloadBuilder();
        final int len = null != b ? b.build().length() : 0;
        return endSeq + len;
    }

    static int rounddown(int a, int b) {
        return a - (a % b);
    }

    static int secureSeq(final byte[] srcAddress, final short srcPort,
                         final byte[] dstAddress, final short dstPort) {
        final long timeBase = (System.nanoTime() - TIME_COUNTER.getAndIncrement()) >> 6;
        try {
            final ByteBuffer buf = ByteBuffer.allocate(
                    srcAddress.length + 2 + dstAddress.length + 2
            ).put(srcAddress).putShort(srcPort).put(dstAddress).putShort(dstPort);

            final MessageDigest digest = MessageDigest.getInstance("MD5");
            final byte[] hash = digest.digest(buf.array());
            final int hashV = ((hash[0] & 0xFF) << 24) | ((hash[1] & 0xFF) << 16) | ((hash[2] & 0xFF) << 8) | ((hash[3] & 0xFF));
            return (int) (timeBase + hashV);
        } catch (final NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }


    public static String logPrefix(final Object id,
                                   final String srcAddr, final int srcPort,
                                   final String dstAddr, final int dstPort) {

        final StringBuilder buff = new StringBuilder();
        if (null != id) {
            buff.append("[").append(id).append("]");
//        } else {
//            buff.append("[").append("????????").append("]");
        }

        buff.append(" ")
            .append(srcAddr).append(":").append(srcPort)
            .append(" -> ")
            .append(dstAddr).append(":").append(dstPort);
        return buff.toString();
    }

    public static String logify(final Object id, final IpHeader ipHeader, final TcpPacket tcpPacket, final int wscale) {
        final InetAddress srcAddr = ipHeader.getSrcAddr();
        final InetAddress dstAddr = ipHeader.getDstAddr();
        final TcpHeader tcpHeader = tcpPacket.getHeader();
        final String srcHostName = srcAddr.getHostAddress();
        final String dstHostName = dstAddr.getHostAddress();
        final int srcPort = tcpHeader.getSrcPort().valueAsInt();
        final int dstPort = tcpHeader.getDstPort().valueAsInt();

        final StringBuilder buff = new StringBuilder();
        buff.append(logPrefix(id, srcHostName, srcPort, dstHostName, dstPort));

        final int len = buff.length();
        if (tcpHeader.getFin()) {
            buff.append("FIN,");
        }
        if (tcpHeader.getSyn()) {
            buff.append("SYN,");
        }
        if (tcpHeader.getRst()) {
            buff.append("RST,");
        }
        if (tcpHeader.getPsh()) {
            buff.append("PSH,");
        }
        if (tcpHeader.getAck()) {
            buff.append("ACK,");
        }
        if (tcpHeader.getUrg()) {
            buff.append("URG,");
        }

        if (buff.length() > len) {
            buff.replace(buff.length() - 1, buff.length(), "] ").insert(len, " [");
        }

        final boolean useRelative = false;
        long sequence = tcpHeader.getSequenceNumberAsLong();
        long acknowledgment = tcpHeader.getAcknowledgmentNumberAsLong();

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
        if (tcpHeader.getAck()) {
            buff.append(" Ack=").append(acknowledgment);
        }

        final int window = tcpHeader.getWindowAsInt() << wscale;
        buff.append(" Win=").append(window);

        final int payloadLen = tcpPacket.length() - tcpHeader.length();
        buff.append(" Len=").append(payloadLen);

        if (tcpHeader.getSyn()) {

        }

        /*
        final Packet payload = tcpPacket.getPayload();
        if (null != payload) {
            buff.append(" ").append(Bytes.toString(payload.getRawData()));
        }
        */
        return buff.toString();
    }
}

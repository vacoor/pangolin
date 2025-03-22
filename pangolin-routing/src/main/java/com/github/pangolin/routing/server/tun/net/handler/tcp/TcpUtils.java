package com.github.pangolin.routing.server.tun.net.handler.tcp;

import org.pcap4j.packet.Packet;
import org.pcap4j.packet.TcpPacket;

import java.util.concurrent.TimeUnit;

/**
 *
 */
abstract class TcpUtils {

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

    static long tcp_jiffies32() {
        return jiffies();
    }

    /*-
     * 定时器相关使用.
     */
    static long jiffies() {
        return msecs_to_jiffies(System.currentTimeMillis());
    }

    static long usecs_to_jiffies(long us) {
        return msecs_to_jiffies(TimeUnit.MICROSECONDS.toMillis(us));
    }

    static long msecs_to_jiffies(long ms) {
        int MSEC_PER_SEC = 1000;
        if (0 == (TcpConstants.HZ % MSEC_PER_SEC)) {
            return (TcpConstants.HZ / MSEC_PER_SEC) * ms;
        }
        return (long) ((TcpConstants.HZ * 1F / MSEC_PER_SEC) * ms);
    }

    static long jiffies_to_usecs(long jiffies) {
        long USEC_PER_SEC = 1000 * 1000;
        if (0 == (USEC_PER_SEC % TcpConstants.HZ)) {
            return USEC_PER_SEC / TcpConstants.HZ * jiffies;
        }
        return (long) ((1000F * 1000 / TcpConstants.HZ) * jiffies);
    }

    static long jiffies_to_msecs(long jiffies) {
        return TimeUnit.MICROSECONDS.toMillis(jiffies_to_usecs(jiffies));
    }

    static long toUint32(final int value) {
        return value & 0xFFFFFFFFL;
    }

    static int ilog2(int a) {
        return (int) (Math.log(a) / Math.log(2));
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
    static int determineEndSeq(final TcpPacket skb) {
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

}

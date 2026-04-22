package com.github.pangolin.routing.acceptor.tun.net.handler.tcp.util;

import com.github.pangolin.routing.acceptor.tun.net.codec.TcpPacketBuf;
import com.github.pangolin.routing.acceptor.tun.net.handler.tcp.internal.TcpBuffer;
import org.bouncycastle.crypto.macs.SipHash;
import org.bouncycastle.crypto.params.KeyParameter;

import java.security.SecureRandom;

/**
 *
 */
public abstract class TcpUtils {

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

    public static boolean time_after(long a, long b) {
        return (b - a) < 0;
    }

    static boolean time_after_eq(long a, long b) {
        return (a - b) >= 0;
    }

    public static boolean time_before(long a, long b) {
        return time_after(b, a);
    }

    public static boolean time_before_eq(long a, long b) {
        return time_after_eq(b, a);
    }

    static long toUint32(final int value) {
        return value & 0xFFFFFFFFL;
    }

    public static int ilog2(int a) {
        return (int) (Math.log(a) / Math.log(2));
    }


    /**
     * Integer log2 via bit-shift, equivalent to the Linux kernel ilog2 macro.
     *
     * <p>Do NOT replace this with {@code Math.log(x) / Math.log(2)}:
     * <ol>
     *   <li>When {@code x == 0}, {@code Math.log(0)} returns {@code -Infinity}; casting
     *       {@code -Infinity} to {@code int} yields {@code Integer.MIN_VALUE} (undefined
     *       behavior per JLS §5.1.3), whereas this method correctly returns {@code 0}.</li>
     *   <li>Floating-point rounding may cause {@code log2(2^n)} to return {@code n-1} for
     *       certain values of {@code n} (e.g. {@code log(65536)/log(2)} may evaluate to
     *       {@code 15.9999...} and truncate to {@code 15} instead of {@code 16}), diverging
     *       from the exact bit-shift semantics of the Linux kernel ilog2 macro.</li>
     * </ol>
     */
    public static int _ilog2(int x) {
        int i = 0;
        while (x >= 2) {
            x = x >> 1;
            i++;
        }
        return i;
    }


    public static int clamp(int value, int min, int max) {
        if (value < min) {
            return min;
        }
        if (value > max) {
            return max;
        }
        return value;
    }

    /**
     * Determine end sequence number from an IpPacketBuf (incoming packet).
     */
    public static int determineEndSeq(final TcpPacketBuf pkt) {
        int endSeq = pkt.tcpSeq();
        if (pkt.isSyn()) {
            endSeq++;
        }
        if (pkt.isFin()) {
            endSeq++;
        }
        return endSeq + pkt.tcpPayloadLength();
    }

    public static int determineEndSeq(final TcpBuffer skb) {
        int endSeq = skb.sequenceNumber();
        if (skb.syn()) {
            endSeq++;
        }
        if (skb.fin()) {
            endSeq++;
        }
        return endSeq + skb.payloadLength();
    }

    public static int rounddown(int a, int b) {
        return a - (a % b);
    }

    private static final byte[] key = new byte[128 / 8];

    static {
        new SecureRandom().nextBytes(key);
    }

    public static int secureSeq(final byte[] srcAddress, final int srcPort,
                                final byte[] dstAddress, final int dstPort) {
        final SipHash sipHash = new SipHash();
        sipHash.init(new KeyParameter(key));
        sipHash.update(dstAddress, 0, dstAddress.length);
        sipHash.update(srcAddress, 0, srcAddress.length);

        sipHash.update((byte) ((dstPort >> 8) & 0xFF));
        sipHash.update((byte) ((dstPort >> 0) & 0xFF));
        sipHash.update((byte) ((srcPort >> 8) & 0xFF));
        sipHash.update((byte) ((srcPort >> 0) & 0xFF));

        final long hash64 = sipHash.doFinal();
        final int hash32 = (int) (hash64 & 0xFFFFFFFFL);
        return seq_scale(hash32);
    }

    static int seq_scale(int seq) {
        return seq + (int) (System.nanoTime() >> 6);
    }

    /**
     * Build unique connection key from IpPacketBuf (incoming packet).
     */
    public static String uniqueKey(final TcpPacketBuf pkt) {
        return uniqueKey(
                pkt.srcAddr().getHostAddress(), pkt.tcpSrcPort(),
                pkt.dstAddr().getHostAddress(), pkt.tcpDstPort()
        );
    }

    public static String uniqueKey(final String srcAddr, final int srcPort, final String dstAddr, final int dstPort) {
        return srcAddr + ":" + srcPort + " => " + dstAddr + ":" + dstPort;
    }
}

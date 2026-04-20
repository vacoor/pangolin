package com.github.pangolin.routing.acceptor.tun.net.v2.tcp.core;

/**
 * TCP sequence number comparisons using 32-bit modular (circular) arithmetic.
 * Handles wrap-around correctly at 2^32.
 *
 * @see <a href="https://github.com/torvalds/linux/blob/master/include/linux/tcp.h">Linux tcp.h</a>
 */
public final class TcpSequence {

    private TcpSequence() {}

    /** @return true if seq2 is strictly after seq1 in circular sequence space */
    public static boolean after(int seq2, int seq1) {
        return seq1 - seq2 < 0;
    }

    /** @return true if seq1 is strictly before seq2 in circular sequence space */
    public static boolean before(int seq1, int seq2) {
        return seq1 - seq2 < 0;
    }

    /** @return true if seq1 &lt;= x &lt;= seq2 in circular sequence space */
    public static boolean between(int seq1, int x, int seq2) {
        return x - seq1 >= 0 && seq2 - x >= 0;
    }
}

package com.github.pangolin.routing.acceptor.tun.net.v2.tcp.core;

import com.github.pangolin.routing.acceptor.tun.net.handler.support.TcpPacketBuf;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.connection.TcpConnection;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.internal.TcpConstants;

import static com.github.pangolin.routing.acceptor.tun.net.handler.tcp.util.TcpClock.tcp_jiffies32;
import static com.github.pangolin.routing.acceptor.tun.net.handler.tcp.util.TcpUtils.determineEndSeq;

public class TcpOutSupport {

    /**
     * RFC 5961 7 [ACK Throttling]
     *
     * @param tp
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_input.c#L3649">tcp_send_challenge_ack</a>
     */
    public static void tcp_send_challenge_ack(final TcpConnection tp, boolean accecn_reflector) {
        /* First check our per-socket dupack rate limit. */
        if (__tcp_oow_rate_limited(tp, tp.lastOowAckTimeMs())) {
            return;
        }

        TcpSegmenter.INSTANCE.sendAck(tp);
        /*
        int ack_limit = tp.ipv4_sysctl_tcp_challenge_ack_limit;
        if (ack_limit == Integer.MAX_VALUE) {
            output.tcp_send_ack(net, tp);
            return;
        }
         */

        /* Then check host-wide RFC 5961 rate limit. */
        /*
        final long now = jiffies() / HZ;
        if (now != tp.ipv4_tcp_challenge_timestamp) {
            int half = (ack_limit + 1) >> 1;
            tp.ipv4_tcp_challenge_timestamp = now;
            tp.ipv4_tcp_challenge_count = get_random_u32_inclusive(half, ack_limit + half - 1);
        }
        int count = tp.ipv4_tcp_challenge_count;
        if (count > 0) {
            tp.ipv4_tcp_challenge_count -= 1;
            output.tcp_send_ack(net, tp);
        }
        */
    }

    /** Rate-limit out-of-window ACKs to avoid ACK storms. */
    public static boolean tcp_oow_rate_limited(TcpConnection conn, final TcpPacketBuf pkt, final long last_oow_ack_time) {
        /* Data packets without SYNs are not likely part of an ACK loop. */
        if (pkt.tcpSeq() != determineEndSeq(pkt) && !pkt.isSyn()) {
            return false;
        }
        return __tcp_oow_rate_limited(conn, last_oow_ack_time);
    }

    private static boolean __tcp_oow_rate_limited(TcpConnection conn, long last_oow_ack_time) {
        if (0 != last_oow_ack_time) {
            final long elapsed = tcp_jiffies32() - last_oow_ack_time;
            if (0 <= elapsed && elapsed < TcpConstants.INVALID_ACK_RATELIMIT_MS) {
                return true;/* rate-limited: don't send yet! */
            }
        }

        conn.lastOowAckTimeMs(tcp_jiffies32());

        return false;    /* not rate-limited: go ahead, send dupack now! */
    }
}

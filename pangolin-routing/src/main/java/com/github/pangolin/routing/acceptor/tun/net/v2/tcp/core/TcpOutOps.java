package com.github.pangolin.routing.acceptor.tun.net.v2.tcp.core;

import com.github.pangolin.routing.acceptor.tun.net.handler.support.TcpPacketBuf;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.connection.TcpConnection;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.internal.TcpConstants;

import static com.github.pangolin.routing.acceptor.tun.net.handler.tcp.util.TcpClock.tcp_jiffies32;
import static com.github.pangolin.routing.acceptor.tun.net.handler.tcp.util.TcpUtils.determineEndSeq;

public class TcpOutOps {

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

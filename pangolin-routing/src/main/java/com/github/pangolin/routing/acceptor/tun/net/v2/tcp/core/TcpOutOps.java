package com.github.pangolin.routing.acceptor.tun.net.v2.tcp.core;

import com.github.pangolin.routing.acceptor.tun.net.handler.support.TcpPacketBuf;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.core.TcpConnection;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.core.TcpConstants;


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

    public static boolean tcp_oow_rate_limited(TcpSock sock, final TcpPacketBuf pkt) {
        if (sock == null || !sock.hasConnection()) {
            return false;
        }
        if (pkt.tcpSeq() != determineEndSeq(pkt) && !pkt.isSyn()) {
            return false;
        }
        if (0 != sock.lastOowAckTimeMs()) {
            final long elapsed = tcp_jiffies32() - sock.lastOowAckTimeMs();
            if (0 <= elapsed && elapsed < TcpConstants.INVALID_ACK_RATELIMIT_MS) {
                return true;
            }
        }
        sock.lastOowAckTimeMs(tcp_jiffies32());
        return false;
    }

    /**
     * SYN_RECV 阶段的 OOW 限流 — 对齐 Linux {@code tcp_check_req} 中的
     * {@code tcp_oow_rate_limited(sock_net(sk), skb, LINUX_MIB_TCPACKSKIPPEDSYNRECV,
     * &tcp_rsk(req)->last_oow_ack_time)}。
     *
     * <p>语义与 ESTABLISHED 路径一致(半秒窗单桶),区别仅在 {@code last_oow_ack_time} 的
     * 归属 —— 此重载读写 {@link TcpHandshaker} 上的时戳,避免污染共享 listen sk。
     *
     * @return {@code true} 表示被限流(应静默丢弃),{@code false} 表示允许发送。
     */
    public static boolean tcp_oow_rate_limited(TcpHandshaker hs, final TcpPacketBuf pkt) {
        if (hs == null) {
            return false;
        }
        if (pkt.tcpSeq() != determineEndSeq(pkt) && !pkt.isSyn()) {
            return false;
        }
        if (0 != hs.lastOowAckTimeMs()) {
            final long elapsed = tcp_jiffies32() - hs.lastOowAckTimeMs();
            if (0 <= elapsed && elapsed < TcpConstants.INVALID_ACK_RATELIMIT_MS) {
                return true;
            }
        }
        hs.lastOowAckTimeMs(tcp_jiffies32());
        return false;
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

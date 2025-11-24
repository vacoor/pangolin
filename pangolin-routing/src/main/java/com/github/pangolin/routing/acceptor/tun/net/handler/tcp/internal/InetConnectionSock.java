package com.github.pangolin.routing.acceptor.tun.net.handler.tcp.internal;

import com.github.pangolin.routing.acceptor.tun.net.handler.tcp.core.TcpTimer;
import com.github.pangolin.routing.acceptor.tun.net.handler.tcp.core.inet_connection_sock_af_ops;
import com.github.pangolin.routing.acceptor.tun.net.handler.tcp.core.request_sock_ops;
import lombok.extern.slf4j.Slf4j;

import static com.github.pangolin.routing.acceptor.tun.net.handler.tcp.internal.TcpConstants.HZ;

/**
 * https://github.com/torvalds/linux/blob/master/include/net/inet_connection_sock.h#L78
 */
@Slf4j
public class InetConnectionSock extends Sock {

    // https://github.com/torvalds/linux/blob/master/include/net/tcp.h#L146
    public static final int TCP_RTO_MAX = 120 * HZ;
    public static final int TCP_RTO_MIN = HZ / 5;
    /**
     * timestamp of retransmission timeout.
     */
    public long icsk_timeout;

    /**
     * Retransmission timeout.
     */
    public int icsk_rto;
    public int icsk_rto_min = TCP_RTO_MIN;
    public int icsk_rto_max = TCP_RTO_MAX;

    public int icsk_delack_max;
    public int icsk_pmtu_cookie;

    public int icsk_retransmits;
    public volatile int icsk_pending;
    public int icsk_backoff;
    public int icsk_syn_retries;
    public int icsk_probes_out;
    public int icsk_ext_hdr_len;

    public final IcskAck icsk_ack = new IcskAck();

    public long icsk_probes_tstamp;
    public int icsk_user_timeout;

    public inet_connection_sock_af_ops icsk_af_ops;


    public static class IcskAck {
        public volatile int pending;
        public int pingpong;
        public int quick;
        public int retry;
        /**
         * ACK timeout.
         * FIXME int
         */
        public long ato = 0;

        /**
         * 最后一次收到数据的时间.
         */
        public long lrcvtime;
        public int last_seg_size;

        public int rcv_mss;

        /**
         * ACK 超时时间(offset).
         * <p>
         * //     * @see #tcp_event_data_recv(TcpPacket)
         *
         * @see TcpOutput#tcp_event_data_sent
         */
        public long timeout;


    }

    /**
     * @see <a href="https://github.com/torvalds/linux/blob/master/include/net/inet_connection_sock.h#L335">inet_csk_exit_pingpong_mode</a>
     */
    public static void inet_csk_exit_pingpong_mode(final InetConnectionSock sk) {
        if (inet_csk_in_pingpong_model(sk)) {
            log.trace("[PING-PONG] exit PING-PONG mode");
        }
        sk.icsk_ack.pingpong = 0;
    }

    /**
     * @see <a href="https://github.com/torvalds/linux/blob/master/include/net/inet_connection_sock.h#L340">inet_csk_in_pingpong_model</a>
     */
    public static boolean inet_csk_in_pingpong_model(final InetConnectionSock sk) {
        return sk.icsk_ack.pingpong >= SysctlOptions.sysctl_tcp_pingpong_thresh;
    }

    /**
     * @see <a href="https://github.com/torvalds/linux/blob/master/include/net/inet_connection_sock.h#L172">inet_csk_schedule_ack</a>
     */
    public static void inet_csk_schedule_ack(final InetConnectionSock sk) {
        sk.icsk_ack.pending |= TcpTimer.ICSK_ACK_SCHED;
    }
}

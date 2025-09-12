package com.github.pangolin.routing.acceptor.tun.net.handler.tcp.v2;

import static com.github.pangolin.routing.acceptor.tun.net.handler.tcp.internal.TcpConstants.HZ;

/**
 * https://github.com/torvalds/linux/blob/master/include/net/inet_connection_sock.h#L78
 */
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

}

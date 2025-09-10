package com.github.pangolin.routing.acceptor.tun.net.handler.tcp.v2;

import com.github.pangolin.routing.acceptor.tun.net.handler.tcp.internal.TcpBuffer;
import com.github.pangolin.routing.acceptor.tun.net.handler.tcp.internal.TcpConnection;

import static com.github.pangolin.routing.acceptor.tun.net.handler.tcp.internal.TcpConstants.TCP_NAGLE_OFF;

/**
 * https://github.com/torvalds/linux/blob/master/include/linux/tcp.h#L194
 */
public class TcpSock extends InetConnectionSock {
    /**
     * 最大窗口.
     *
     * @see <a href="https://github.com/torvalds/linux/blob/master/include/linux/tcp.h#L192">tcp_sock</a>
     */
    public int max_window;
    public int rcv_ssthresh;


    public int snd_wnd;
    /**
     * 缓存发送方当前有效的MSS, 根据pmtu变动.
     */
    public int mss_cache;
    public int snd_cwnd;
    public int tcp_header_len;
    // TSval values in usec (使用微妙还是毫秒)
    public int tcp_usec_ts;
    public int copied_seq;
    /*-
     * timestamp of last received ACK (for keepalives).
     */
    public long rcv_tstamp;
    /**
     * 触发窗口更新的序号.
     * Sequence for window update.
     */
    public int snd_wl1;
    public long rttvar_us;
    public int retrans_out;
    /**
     * 本端能接收的最大MSS, 通告对端的MSS.
     */
    public int advmss;

    public int snd_ssthresh;

    public int segs_out;
    public int data_segs_out;
    public int bytes_sent;

    /**
     * 小包(small)发送的结束序号.
     */
    public int snd_sml;
    /**
     * 下一个写入发送队列的序号.
     */
    public int write_seq;
    /**
     * Last pushed seq, required to talk to windows.
     *
     * @see <a href="https://github.com/torvalds/linux/blob/master/include/linux/tcp.h#L264">pushed_seq</a>
     */
    public int pushed_seq;
    /**
     * Last send time.
     */
    public long lsndtime;
    public long mdev_us;
    public int rtt_seq;
    public long tcp_wstamp_ns;


    /**
     * nano seconds
     */
    public long tcp_clock_cache;
    /*-
     * most recent packet received/sent.
     * us (micro seconds).
     */
    public long tcp_mstamp;
    public int rcv_nxt;
    public int snd_una;
    public int snd_nxt;
    public int window_clamp;
    public long srtt_us;
    /**
     * 已发送未ACK的数据包数量.
     *
     * @see TcpOutput#tcp_event_new_data_sent(TcpConnection, TcpBuffer)
     * @see TcpInput#tcp_clean_rtx_queue(TcpConnection, int)
     */
    public int packets_out;
    /**
     * Urgent pointer.
     *
     * @deprecated
     */
    @Deprecated
    public int snd_up;
    public int delivered;
    public int rcv_wnd;

    public final tcp_options_received rx_opt = new tcp_options_received();

    public long bytes_received;
    public int rcv_wup;
    public int bytes_acked;

    public final RcvRttEst rcv_rtt_est = new RcvRttEst();

    public int nonagle = TCP_NAGLE_OFF;


    // ...

    public long mdev_max_us;

    // ...

    public int snd_cwnd_clamp;

    // ...

    public long retrans_stamp;
    public long undo_retrans;
    public int bytes_retrans;
    public int total_retrans;
    public long rto_stamp;
    public int total_rto;
    // FIXME
    // https://github.com/torvalds/linux/blob/master/net/ipv4/tcp.c#L422
    public int total_rto_recoveries;
    public int keepalive_intvl;
    public int linger2;


    public class RcvRttEst {
        public long rtt_us;
        public int seq;
        public long time;
    }
}

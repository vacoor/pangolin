package com.github.pangolin.routing.acceptor.tun.net.handler.tcp.v2;

import com.github.pangolin.routing.acceptor.tun.net.handler.tcp.internal.TcpBuffer;
import com.github.pangolin.routing.acceptor.tun.net.handler.tcp.internal.TcpConnection;

import java.util.concurrent.ConcurrentLinkedQueue;

import static com.github.pangolin.routing.acceptor.tun.net.handler.tcp.internal.TcpClock.nsecs_to_jiffies;
import static com.github.pangolin.routing.acceptor.tun.net.handler.tcp.internal.TcpConstants.TCP_NAGLE_OFF;
import static com.github.pangolin.routing.acceptor.tun.net.handler.tcp.internal.TcpUtils.ilog2;

/**
 * https://github.com/torvalds/linux/blob/master/include/linux/tcp.h#L194
 */
public class TcpSock extends InetConnectionSock {
    /*-
     *              |<------- TCP recv window ------->|
     *              |            (RCV.WND)            |
     *  --------------------------------------------------------------------
     * | .. | 2 | 3 | 4 | 5 | 6 | 7 | 8 | 9 | 10 | 11 | 12 | 13 | 14 |  15  | ...
     *  --------------------------------------------------------------------
     * |  sent and  | sent and not  |                 | can't receive until |
     * |acknowledged| acknowledged  |                 |    window moves     |
     *              ^               ^                 ^
     *              |-closes->   RCV.NXT    <-shrinks-|-opens->
     *          left edge                        right edge
     *          (RCV.WUP)                    (RCV.WUP + RCV.WND)
     *
     */


    /*-
     *              |<------- TCP send window ------->|
     *              |            (SND.WND)            |
     *              |               |<-Usable window->|
     *  --------------------------------------------------------------------
     * | .. | 2 | 3 | 4 | 5 | 6 | 7 | 8 | 9 | 10 | 11 | 12 | 13 | 14 |  15  | ...
     *  --------------------------------------------------------------------
     * |  sent and  | sent and not  |    being sent   |   can't send until  |
     * |acknowledged| acknowledged  |                 |     window moves    |
     *              ^               ^                 ^
     *              |-closes->    SND.NXT   <-shrinks-|-opens->
     *          left edge                        right edge
     *          (SND.UNA)                    (SND.UNA + SND.WND)
     *
     * Usable window = snd.una + snd.wnd - snd.nxt
     */

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

    public long snd_cwnd_stamp;

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

    public long last_oow_ack_time;
    public boolean compressed_ack;
    public int keepalive_probes;

    public ConcurrentLinkedQueue<TcpBuffer> sk_write_queue = new ConcurrentLinkedQueue<>();
    public ConcurrentLinkedQueue<TcpBuffer> tcp_rtx_queue = new ConcurrentLinkedQueue<>();


    /**
     * @see <a href="https://github.com/torvalds/linux/blob/master/include/net/tcp.h#L1356">tcp_left_out</a>
     */
    public int tcp_left_out() {
        // FIXME return tp->sacked_out + tp->lost_out;
        return 0;
    }

    /**
     * This determines how many packets are "in the network" to the best
     * of our knowledge.  In many cases it is conservative, but where
     * detailed information is available from the receiver (via SACK
     * blocks etc.) we can make more aggressive calculations.
     * <p>
     * Use this for decisions involving congestion control, use just
     * tp->packets_out to determine if the send queue is empty or not.
     * <p>
     * Read this equation as:
     * <p>
     * "Packets sent once on transmission queue" MINUS
     * "Packets left network, but not honestly ACKed yet" PLUS
     * "Packets fast retransmitted"
     *
     * @see <a href="https://github.com/torvalds/linux/blob/master/include/net/tcp.h#L1375">tcp_packets_in_flight</a>
     */
    public int tcp_packets_in_flight() {
        return packets_out - tcp_left_out() + retrans_out;
    }

    /**
     * 发送可用/拥塞窗口大小.
     *
     * @see <a href="https://github.com/torvalds/linux/blob/master/include/net/tcp.h#L1382">tcp_snd_cwnd</a>
     */
    public int tcp_snd_cwnd() {
        return snd_cwnd;
    }

    /**
     * @see <a href="https://github.com/torvalds/linux/blob/master/include/net/tcp.h#L1387">tcp_snd_cwnd_set</a>
     */
    public void tcp_snd_cwnd_set(final int cwnd) {
        snd_cwnd = cwnd;
    }

    /**
     * 发送窗口右边界.
     * Returns end sequence number of the receiver's advertised window.
     *
     * @see <a href="https://github.com/torvalds/linux/blob/master/include/net/tcp.h#L1440">tcp_wnd_end</a>
     */
    public int tcp_wnd_end() {
        return snd_una + snd_wnd;
    }

    /**
     * Estimates in how many jiffies next packet for this flow can be sent.
     * Scheduling a retransmit timer too early would be silly.
     *
     * @see <a href="https://github.com/torvalds/linux/blob/master/include/net/tcp.h#L1486">tcp_pacing_delay</a>
     */
    protected long tcp_pacing_delay() {
        final long delay = tcp_wstamp_ns - tcp_clock_cache;
        return delay > 0 ? nsecs_to_jiffies(delay) : 0;
    }

    /**
     * Something is really bad, we could not queue an additional packet,
     * because qdisc is full or receiver sent a 0 window, or we are paced.
     * We do not want to add fuel to the fire, or abort too early,
     * so make sure the timer we arm now is at least 200ms in the future,
     * regardless of current icsk_rto value (as it could be ~2ms)
     *
     * @see <a href="https://github.com/torvalds/linux/blob/master/include/net/tcp.h#L1510">tcp_probe0_base</a>
     */
    public long tcp_probe0_base() {
        return Math.max(icsk_rto, TCP_RTO_MIN);
    }

    /**
     * Variant of inet_csk_rto_backoff() used for zero window probes.
     *
     * @see <a href="https://github.com/torvalds/linux/blob/master/include/net/tcp.h#L1516">tcp_probe0_when</a>
     */
    public long tcp_probe0_when(int max_when) {
        final int backoff = Math.min(ilog2(TCP_RTO_MAX / TCP_RTO_MIN) + 1, icsk_backoff);
        final long when = tcp_probe0_base() << backoff;
        return Math.min(when, max_when);
    }


    public class RcvRttEst {
        public long rtt_us;
        public int seq;
        public long time;
    }
}

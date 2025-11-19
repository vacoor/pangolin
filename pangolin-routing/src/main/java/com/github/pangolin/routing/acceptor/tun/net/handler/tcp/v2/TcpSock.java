package com.github.pangolin.routing.acceptor.tun.net.handler.tcp.v2;

import com.github.pangolin.routing.acceptor.tun.net.handler.tcp.core.TcpTimer;
import com.github.pangolin.routing.acceptor.tun.net.handler.tcp.internal.*;
import io.netty.channel.Channel;
import lombok.extern.slf4j.Slf4j;
import org.pcap4j.packet.IpPacket;
import org.pcap4j.packet.TcpPacket;

import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;

import static com.github.pangolin.routing.acceptor.tun.net.handler.tcp.internal.SysctlOptions.sysctl_tcp_fin_timeout;
import static com.github.pangolin.routing.acceptor.tun.net.handler.tcp.internal.TcpClock.*;
import static com.github.pangolin.routing.acceptor.tun.net.handler.tcp.internal.TcpConstants.HZ;
import static com.github.pangolin.routing.acceptor.tun.net.handler.tcp.internal.TcpConstants.TCP_NAGLE_OFF;
import static com.github.pangolin.routing.acceptor.tun.net.handler.tcp.core.TcpTimer.*;
import static com.github.pangolin.routing.acceptor.tun.net.handler.tcp.internal.TcpUtils.*;

/**
 * https://github.com/torvalds/linux/blob/master/include/linux/tcp.h#L194
 */
@Slf4j
public class TcpSock extends InetConnectionSock {
    public static final short IP_HEADER_SIZE = 20;
    public static final short TCP_HEADER_SIZE = 20;

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
     * x@see TcpOutput#tcp_event_new_data_sent(TcpDemultiplexer, TcpBuffer)
     * x@see TcpInput#tcp_clean_rtx_queue(TcpDemultiplexer, int)
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

    public long ipv4_sysctl_tcp_invalid_ratelimit = HZ / 2;
    public int ipv4_sysctl_tcp_challenge_ack_limit = HZ / 2;
    public long ipv4_tcp_challenge_timestamp;
    public int ipv4_tcp_challenge_count;

    public ConcurrentLinkedQueue<TcpBuffer> sk_write_queue = new ConcurrentLinkedQueue<>();
    public ConcurrentLinkedQueue<TcpBuffer> tcp_rtx_queue = new ConcurrentLinkedQueue<>();


    public Runnable icsk_retransmit_timer;
    public Runnable icsk_delack_timer;
    public Runnable sk_timer;



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

    public long tcp_time_stamp_ts() {
        // https://github.com/torvalds/linux/blob/master/include/net/tcp.h#L934
        // ???
        TcpSock tp = this;
        if (tp.tcp_usec_ts > 0) {
            return tcp_mstamp;
        }
        return tcp_time_stamp_ms();
    }

    public long tcp_time_stamp_ms() {
        return TimeUnit.MICROSECONDS.toMillis(tcp_mstamp);
    }


    /**
     * @see <a href="https://github.com/torvalds/linux/blob/master/include/net/inet_connection_sock.h#L177">inet_csk_ack_scheduled</a>
     */
    public boolean inet_csk_ack_scheduled() {
        return 0 != (icsk_ack.pending & ICSK_ACK_SCHED);
    }

    /**
     * @see <a href="https://github.com/torvalds/linux/blob/master/include/net/inet_connection_sock.h#L329">inet_csk_enter_pingpong_mode</a>
     */
    public void inet_csk_enter_pingpong_mode() {
        log.trace("[PING-PONG] enter PING-PONG mode, PING-PONG threshold = {}", SysctlOptions.sysctl_tcp_pingpong_thresh);
        icsk_ack.pingpong = SysctlOptions.sysctl_tcp_pingpong_thresh;
    }


    /**
     * @see <a href="https://github.com/torvalds/linux/blob/master/include/net/inet_connection_sock.h#L346">inet_csk_inc_pingpong_cnt</a>
     */
    public void inet_csk_inc_pingpong_cnt() {
        if (icsk_ack.pingpong < TcpConstants.U8_MAX) {
            log.trace("[PING-PONG] increment PING-PONG count: {} -> {}", icsk_ack.pingpong, icsk_ack.pingpong + 1);
            icsk_ack.pingpong++;
        }
    }

    /**
     * @see <a href="https://github.com/torvalds/linux/blob/master/include/net/tcp.h#L357">tcp_dec_quickack_mode</a>
     */
    public void tcp_dec_quickack_mode() {
        if (icsk_ack.quick != 0) {
            /* How many ACKs S/ACKing new data have we sent? */
            final int pkts = inet_csk_ack_scheduled() ? 1 : 0;

            if (pkts >= icsk_ack.quick) {
                log.trace("[QUICK-ACK] decrement QUICK-ARK count: {} -> {}", icsk_ack.quick, 0);
                icsk_ack.quick = 0;
                /* Leaving quickack mode we deflate ATO. */
                icsk_ack.ato = TcpConstants.TCP_ATO_MIN;
            } else if (pkts != 0) {
                log.trace("[QUICK-ACK] decrement QUICK-ARK count: {} -> {}", icsk_ack.quick, icsk_ack.quick - pkts);
                icsk_ack.quick -= pkts;
            }
        }
    }

    public long icsk_timeout() {
        return icsk_timeout;
    }

    public int tcp_fin_time() {
        // https://github.com/torvalds/linux/blob/master/include/net/tcp.h#L1746
        int fin_timeout = 0 != linger2 ? linger2 : sysctl_tcp_fin_timeout;
        int rto = icsk_rto;

        // 3.5 * rto
        if (fin_timeout < (rto << 2) - (rto >> 1)) {
            fin_timeout = (rto << 2) - (rto >> 1);
        }

        return fin_timeout;
    }

    // https://github.com/torvalds/linux/blob/master/include/net/tcp.h#L2448
    public long tcp_rto_delta_us() {
        final int rto = icsk_rto;
        final TcpBuffer skb = tcp_rtx_queue_head();
        if (null != skb) {
            final long rto_time_stamp_us = tcp_skb_timestamp_us(skb) + jiffies_to_usecs(rto);
            return (rto_time_stamp_us - tcp_mstamp);
        } else {
            log.warn("RTX queue empty");
            return jiffies_to_usecs(rto);
        }
    }

    public int tcp_rto_max() {
        return icsk_rto_max;
    }

    /**
     * @see <a href="https://github.com/torvalds/linux/blob/master/include/net/tcp.h#L905">tcp_skb_timestamp_us</a>
     * https://github.com/torvalds/linux/blob/v6.13/include/linux/skbuff.h#L867
     */
    public long tcp_skb_timestamp_us(final TcpBuffer skb) {
        // skb_mstamp_ns <==> skb->tstamp
        // return div_u64(skb->skb_mstamp_ns, NSEC_PER_USEC);
        // FIXME
        return (skb.tstamp / 1000);
    }

    /**
     * @see <a href="https://github.com/torvalds/linux/blob/master/include/net/tcp.h#L2003">tcp_rtx_queue_head</a>
     */
    public TcpBuffer tcp_rtx_queue_head() {
        return tcp_rtx_queue.peek();
    }


    public void inet_csk_clear_xmit_timer(TcpTimer timer, int what) {
        // https://github.com/torvalds/linux/blob/master/include/net/inet_connection_sock.h#L195
        if (ICSK_TIME_RETRANS == what || TcpTimer.ICSK_TIME_PROBE0 == what) {
            icsk_pending = 0;
            // stop icsk_retransmit_timer
            timer.sk_stop_timer(icsk_retransmit_timer);
        }
        if (TcpTimer.ICSK_TIME_DACK == what) {
            icsk_ack.pending = 0;
            icsk_ack.retry = 0;
            timer.sk_stop_timer(icsk_delack_timer);
        }
    }

    /**
     * @see <a href="https://github.com/torvalds/linux/blob/master/include/net/tcp.h#L2021">tcp_send_head</a>
     */
    public TcpBuffer tcp_send_head() {
        return sk_write_queue.peek();
    }

    // https://github.com/torvalds/linux/blob/master/include/linux/tcp.h#L597
    public static int tcp_mss_clamp(final TcpSock tp, final int mss) {
        final int user_mss = tp.rx_opt.user_mss;
        return user_mss > 0 && user_mss < mss ? user_mss : mss;
    }

    public boolean tcp_write_queue_empty() {
        return sk_write_queue.isEmpty();
    }

    /**
     * @see <a href="https://github.com/torvalds/linux/blob/master/include/net/tcp.h#L1483">tcp_update_wl</a>
     */
    public void tcp_update_wl(int ack_seq) {
        snd_wl1 = ack_seq;
    }

    /**
     * @see <a href="https://github.com/torvalds/linux/blob/master/include/net/tcp.h#L1478">tcp_init_wl</a>
     */
    public void tcp_init_wl(int seq) {
        snd_wl1 = seq;
    }

    /**
     * @see <a href="https://www.cnblogs.com/aiwz/p/6333260.html">零窗口探测/坚持/持续定时器</a>
     * @see <a href="https://github.com/torvalds/linux/blob/master/include/net/tcp.h#L1526">tcp_check_probe_timer</a>
     */
    public void tcp_check_probe_timer(TcpTimer timer) {
        if (0 == packets_out && 0 == icsk_pending) {
            tcp_reset_xmit_timer(timer, ICSK_TIME_PROBE0, tcp_probe0_base(), true);
        }
    }

    /**
     * @see <a href="https://github.com/torvalds/linux/blob/master/include/net/tcp.h#L1423">tcp_reset_xmit_timer</a>
     */
    public void tcp_reset_xmit_timer(TcpTimer timer, final int what, long when, final boolean pace_delay) {
        if (pace_delay) {
            when += tcp_pacing_delay();
        }
        timer.inet_csk_reset_xmit_timer(this, what, when, tcp_rto_max());
    }

    public int tcp_bound_to_half_wnd(int pktsize) {
        // https://github.com/torvalds/linux/blob/master/include/net/tcp.h#L704
        int cutoff;
        if (max_window > TcpConstants.TCP_MSS_DEFAULT) {
            cutoff = max_window >> 1;
        } else {
            cutoff = max_window;
        }
        if (cutoff > 0 && pktsize > cutoff) {
            return Math.max(cutoff, 68 - tcp_header_len);
        }
        return pktsize;
    }

    public int dst_mtu() {
        return 1500;
    }

    // https://github.com/torvalds/linux/blob/v6.13/include/linux/skbuff.h#L4322
    public void skb_set_delivery_time(TcpBuffer skb, long kt, String tstamp_type) {
        // FIXME
//        skb.tstamp = kt;
        skb.skb_mstamp_ns = kt;
        skb.tstamp = kt;
    }

    public int dst_metric_advmss() {
        // https://github.com/torvalds/linux/blob/master/include/net/dst.h#L182
        return 1500 - IP_HEADER_SIZE - TCP_HEADER_SIZE;
    }

    public int dst_metric(int metric) {
        return 0;
    }

    public int tcp_skb_pcount(TcpBuffer skb) {
        return 1;
    }

    public long tcp_skb_timestamp_ts(int usec_ts, TcpBuffer skb) {
        // FIXME
        long skb_mstamp_ns = skb.skb_mstamp_ns;
        if (usec_ts != 0) {
            // skb_mstamp_ns / NSEC_PER_USEC;
            return TimeUnit.NANOSECONDS.toMicros(skb_mstamp_ns);
        }
        // skb_mstamp_ns / NSEC_PER_MSEC
        return TimeUnit.NANOSECONDS.toMillis(skb_mstamp_ns);
    }



    // https://github.com/torvalds/linux/blob/master/net/ipv4/tcp.c#L665
    private void tcp_mark_push(final TcpPacket.Builder skb) {
        skb.psh(true);
        pushed_seq = write_seq;
    }

    // https://github.com/torvalds/linux/blob/master/net/ipv4/tcp.c#L671
    private boolean forced_push() {
        // ???
        return after(write_seq, pushed_seq + (max_window >> 1));
    }

    // https://github.com/torvalds/linux/blob/master/net/ipv4/tcp.c#L676

    // https://github.com/torvalds/linux/blob/master/net/ipv4/tcp.c#L719
    void tcp_push(int flags, int mss_now, int nonagle, int size_goal) {
        // FIXME ....
    }





    public void tcp_ack_tstamp() {

    }


    void logTrace(final String format, final Object... args) {
        log.trace(format(format), args);
    }

    void logDebug(final String format, final Object... args) {
        log.debug(format(format), args);
    }

    void logInfo(final String format, final Object... args) {
        log.info(format(format), args);
    }

    void logWarn(final String format, final Object... args) {
        log.warn(format(format), args);
    }

    void logError(final String format, final Object... args) {
        log.error(format(format), args);
    }

    private String format(final String format) {
//        final InetAddress srcAddr = ipHeader.getSrcAddr();
//        final InetAddress dstAddr = ipHeader.getDstAddr();

        final int srcPort = this.srcPort.valueAsInt();
        final int dstPort = this.dstPort.valueAsInt();

        final String srcHostAddr = srcAddr.getHostAddress();
        final String dstHostAddr = dstAddr.getHostAddress();


        final StringBuilder buff = new StringBuilder();
        buff.append(TcpUtils.logPrefix(null != child ? innerChannel(this).id() : null, srcHostAddr, srcPort, dstHostAddr, dstPort));
        buff.append(" ");

        buff.append(format);
        return buff.toString();
    }

    private static Channel innerChannel(SockCommon sock) {
        return sock.child.channel();
    }

    public static void debug(final SockCommon sk, final IpPacket.IpHeader ipHeader, final TcpPacket tcpPacket, boolean inbound) {
        tcp_options_received rx_opt = sk instanceof TcpSock ? ((TcpSock) sk).rx_opt : new tcp_options_received();
        final String message = TcpUtils.logify(null != sk.child ? innerChannel(sk).id() : null, ipHeader, tcpPacket, inbound ? rx_opt.rcv_wscale : rx_opt.snd_wscale);
        log.debug(message);
    }

    public void tcp_send_challenge_ack() {
        // https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_input.c#L3649
    }

    public long icsk_delack_timeout() {
        // FIXME
        return icsk_ack.timeout;
    }



    public class RcvRttEst {
        public long rtt_us;
        public int seq;
        public long time;
    }
}

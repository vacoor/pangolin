package com.github.pangolin.routing.acceptor.tun.net.handler.tcp.core;

import com.github.pangolin.routing.acceptor.tun.net.handler.tcp.internal.*;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.channel.*;
import io.netty.util.ReferenceCountUtil;
import lombok.extern.slf4j.Slf4j;
import org.pcap4j.packet.IpPacket;
import org.pcap4j.packet.TcpPacket;
import org.pcap4j.packet.UnknownPacket;

import java.io.IOException;
import java.security.SecureRandom;

import static com.github.pangolin.routing.acceptor.tun.net.handler.tcp.core.TcpDemultiplexer.*;
import static com.github.pangolin.routing.acceptor.tun.net.handler.tcp.core.TcpHandshaker.tcpLogError;
import static com.github.pangolin.routing.acceptor.tun.net.handler.tcp.core.TcpOutput.tcp_mstamp_refresh;
import static com.github.pangolin.routing.acceptor.tun.net.handler.tcp.core.TcpTimer.*;
import static com.github.pangolin.routing.acceptor.tun.net.handler.tcp.internal.InetConnectionSock.*;
import static com.github.pangolin.routing.acceptor.tun.net.handler.tcp.internal.TcpConstants.*;
import static com.github.pangolin.routing.acceptor.tun.net.handler.tcp.internal.TcpDropReason.*;
import static com.github.pangolin.routing.acceptor.tun.net.handler.tcp.internal.TcpSock.inet_csk_schedule_ack;
import static com.github.pangolin.routing.acceptor.tun.net.handler.tcp.internal.TcpState.*;
import static com.github.pangolin.routing.acceptor.tun.net.handler.tcp.util.TcpClock.*;
import static com.github.pangolin.routing.acceptor.tun.net.handler.tcp.util.TcpUtils.*;
import static org.pcap4j.packet.TcpPacket.TcpHeader;

@Slf4j
public class TcpInput {
    /**
     * Incoming frame contained data.
     *
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_input.c#L87">FLAG_DATA</a>
     */
    private static final int FLAG_DATA = 0x01;

    /**
     * Incoming ACK was a window update.
     *
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_input.c#L87">FLAG_WIN_UPDATE</a>
     */
    private static final int FLAG_WIN_UPDATE = 0x02;

    /* "" "" some of which was retransmitted.	*/
    private static final int FLAG_RETRANS_DATA_ACKED = 0x08;

    /**
     * Do not skip RFC checks for window update.
     *
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_input.c#L97">FLAG_SLOWPATH</a>
     */
    static final int FLAG_SLOWPATH = 0x100;

    /**
     * Snd_una was changed (!= FLAG_DATA_ACKED).
     *
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_input.c#L97">FLAG_SND_UNA_ADVANCED</a>
     */
    private static final int FLAG_SND_UNA_ADVANCED = 0x400;

    static final int FLAG_UPDATE_TS_RECENT = 0x4000;

    static final int FLAG_NO_CHALLENGE_ACK = 0x8000;

    // FIXME
    private static final int CA_ACK_WIN_UPDATE = FLAG_WIN_UPDATE;
    private static final int CA_ACK_SLOWPATH = FLAG_SLOWPATH;

    private TcpDemultiplexer demultiplexer;
    private final TcpOutput output;

    public TcpInput(TcpDemultiplexer demultiplexer, final TcpOutput output) {
        this.demultiplexer = demultiplexer;
        this.output = output;
    }


    /**
     * Adapt the MSS value used to make delayed ack decision to the real world.
     *
     * @param sk
     * @param ipPacket
     * @param tcpPacket
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_input.c#L227">tcp_measure_rcv_mss</a>
     */
    private void tcp_measure_rcv_mss(final TcpSock sk, final IpPacket ipPacket, final TcpPacket tcpPacket) {
        // FIXME
        final int lss = sk.icsk_ack.last_seg_size;

        sk.icsk_ack.last_seg_size = 0;
        final int len = tcpPacket.length() - tcpPacket.getHeader().length();
        if (len >= sk.icsk_ack.rcv_mss) {
            /*
            if (len != icsk_ack.rcv_mss) {
                len << TCP_RMEM_TO_WIN_SCALE;
            }
            */

            sk.icsk_ack.rcv_mss = Math.min(len, sk.advmss);
        }
    }

    /**
     * If the current remaining number of quickly ACKs is
     * less than the recalculated number of quickly ACKs, increment it.
     *
     * @param max_quickacks the maximum times of quickly ACKs allowed
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_input.c#L300">tcp_incr_quickack</a>
     */
    private void tcp_incr_quickack(final TcpSock sk, final int max_quickacks) {
        int quickacks = sk.rcv_wnd / (sk.icsk_ack.rcv_mss << 1);
        if (0 == quickacks) {
            quickacks = 2;
        }
        quickacks = Math.min(quickacks, max_quickacks);
        if (quickacks > sk.icsk_ack.quick) {
            log.trace("[QUICK-ACK] increment QUICK-ARK count: {} -> {}", sk.icsk_ack.quick, quickacks);
            sk.icsk_ack.quick = quickacks;
        }
    }

    /**
     * Enter quickly ACK mode and calculate the number of quickly ACKs,
     * exit ping-pong mode, and reset the delay ACK timeout.
     *
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_input.c#L318">tcp_enter_quickack_mode</a>
     */
    private void tcp_enter_quickack_mode(final TcpSock sk, final int max_quickacks) {
        log.trace("[QUICK-ACK] enter QUICK-ARK count: {} -> {}", sk.icsk_ack.quick, max_quickacks);
        tcp_incr_quickack(sk, max_quickacks);
        inet_csk_exit_pingpong_mode(sk);
        sk.icsk_ack.ato = TCP_ATO_MIN;
    }

    /*-
     * Send ACKs quickly, if "quick" count is not exhausted
     * and the session is not interactive.
     *
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_input.c#L318">tcp_in_quickack_mode</a>
     */
    private boolean tcp_in_quickack_mode(final TcpSock sk) {
        return sk.icsk_ack.quick > 0 && !inet_csk_in_pingpong_model(sk);
    }

    /**
     * Updates the delivered and delivered_ce counts.
     *
     * <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_input.c#L483">tcp_count_delivered</a>
     */
    private void tcp_count_delivered(final TcpSock tp, final int delivered, final boolean ece_ack) {
        tp.delivered += delivered;
        // FIXME
    }

    // ...

    /**
     * Initialize RCV_MSS value.
     * <p>
     * RCV_MSS is an our guess about MSS used by the peer.
     * We haven't any direct information about the MSS.
     * It's better to underestimate the RCV_MSS rather than overestimate.
     * Overestimations make us ACKing less frequently than needed.
     * Underestimations are more easy to detect and fix by tcp_measure_rcv_mss().
     *
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_input.c#L622">tcp_initialize_rcv_mss</a>
     */
    void tcp_initialize_rcv_mss(final TcpSock sk) {
        int hint = Math.min(sk.advmss, sk.mss_cache);
        hint = Math.min(hint, sk.rcv_wnd >> 1);
        hint = Math.min(hint, TCP_MSS_DEFAULT);
        hint = Math.max(hint, TCP_MIN_MSS);
        sk.icsk_ack.rcv_mss = hint;
    }

    /**
     * Receiver "autotuning" code.
     * <p>
     * The algorithm for RTT estimation w/o timestamps is based on
     * Dynamic Right-Sizing (DRS) by Wu Feng and Mike Fisk of LANL.
     * <https://public.lanl.gov/radiant/pubs.html#DRS>
     * <p>
     * More detail on this code can be found at
     * <http://staff.psc.edu/jheffner/>,
     * though this reference is out of date.  A new paper
     * is pending.
     *
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_input.c#L652">tcp_rcv_rtt_update</a>
     */
    private void tcp_rcv_rtt_update(final TcpSock sk, final long sample, final int win_dep) {
        long new_sample = sk.rcv_rtt_est.rtt_us;
        long m = sample;

        if (new_sample != 0) {
            /* If we sample in larger samples in the non-timestamp
             * case, we could grossly overestimate the RTT especially
             * with chatty applications or bulk transfer apps which
             * are stalled on filesystem I/O.
             *
             * Also, since we are only going for a minimum in the
             * non-timestamp case, we do not smooth things out
             * else with timestamps disabled convergence takes too
             * long.
             */
            if (0 == win_dep) {
                m -= (new_sample >> 3);
                new_sample += m;
            } else {
                m <<= 3;
                if (m < new_sample) {
                    new_sample = m;
                }
            }
        } else {
            /* No previous measure. */
            new_sample = m << 3;
        }

        sk.rcv_rtt_est.rtt_us = new_sample;
    }

    /**
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_input.c#L684">tcp_rcv_rtt_measure</a>
     */
    private void tcp_rcv_rtt_measure(final TcpSock tp) {
        if (tp.rcv_rtt_est.time != 0) {
            if (before(tp.rcv_nxt, tp.rcv_rtt_est.seq)) {
                return;
            }
            long delta_us = tcp_stamp_us_delta(tp.tcp_mstamp, tp.rcv_rtt_est.time);
            if (delta_us == 0) {
                delta_us = 1;
            }
            tcp_rcv_rtt_update(tp, delta_us, 1);
        }

        tp.rcv_rtt_est.seq = tp.rcv_nxt + tp.rcv_wnd;
        tp.rcv_rtt_est.time = tp.tcp_mstamp;
    }

    // ...

    /**
     * There is something which you must keep in mind when you analyze the
     * behavior of the tp->ato delayed ack timeout interval.  When a
     * connection starts up, we want to ack as quickly as possible.  The
     * problem is that "good" TCP's do slow start at the beginning of data
     * transmission.  The means that until we send the first few ACK's the
     * sender will sit on his end and only queue most of his data, because
     * he can only send snd_cwnd unacked packets at any given time.  For
     * each ACK we send, he increments snd_cwnd and transmits more of his
     * queue.
     *
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_input.c#L820">tcp_event_data_recv</a>
     */
    private void tcp_event_data_recv(final TcpSock sk, final IpPacket ipPacket, final TcpPacket tcpPacket) {
        inet_csk_schedule_ack(sk);
        tcp_measure_rcv_mss(sk, ipPacket, tcpPacket);
        tcp_rcv_rtt_measure(sk);

        final long now = tcp_jiffies32();
        if (0 == sk.icsk_ack.ato) {
            /*
             * The _first_ data packet received, initialize
             * delayed ACK engine.
             */
            tcp_incr_quickack(sk, TCP_MAX_QUICKACKS);
            sk.icsk_ack.ato = TCP_ATO_MIN;
        } else {
            long m = now - sk.icsk_ack.lrcvtime;
            /*-
             * 1. 如果两次收到数据的间隔 <= TCP_ATO_MIN / 2, ato = ato / 2 + TCP_ATO_MIN / 2
             * 2. 如果收到数据间隔 > TCP_ATO_MIN / 2 && < ato, ato = ato / 2 + 间隔, 最大不超过rto
             */
            if (m <= TCP_ATO_MIN / 2) {
                /* The fastest case is the first. */
                sk.icsk_ack.ato = (sk.icsk_ack.ato >> 1) + TCP_ATO_MIN / 2;
            } else if (m < sk.icsk_ack.ato) {
                sk.icsk_ack.ato = (sk.icsk_ack.ato >> 1) + m;
                if (sk.icsk_ack.ato > sk.icsk_rto) {
                    sk.icsk_ack.ato = sk.icsk_rto;
                }
            } else if (m > sk.icsk_ack.ato) {
                /*-
                 * Too long gap. Apparently sender failed to
                 * restart window, so that we send ACKs quickly.
                 */
                tcp_incr_quickack(sk, TCP_MAX_QUICKACKS);
            }
        }
        sk.icsk_ack.lrcvtime = now;

        // ...

        TcpPacket.TcpHeader th = tcpPacket.getHeader();
        int len = tcpPacket.length() - th.length();
        if (len >= 128) {
            // tcp_grow_window(sk, skb, true);
        }
    }

    /**
     * Called to compute a smoothed rtt estimate. The data fed to this
     * routine either comes from timestamps, or from segments that were
     * known _not_ to have been retransmitted [see Karn/Partridge
     * Proceedings SIGCOMM 87]. The algorithm is from the SIGCOMM 88
     * piece by Van Jacobson.
     * NOTE: the next three routines used to be one big routine.
     * To save cycles in the RFC 1323 implementation it was better to break
     * it up into three procedures. -- erics
     */
    void tcp_rtt_estimator(final TcpSock sk, final long mrtt_us) {
        long m = mrtt_us; /* RTT */
        long srtt = sk.srtt_us;

        /*	The following amusing code comes from Jacobson's
         *	article in SIGCOMM '88.  Note that rtt and mdev
         *	are scaled versions of rtt and mean deviation.
         *	This is designed to be as fast as possible
         *	m stands for "measurement".
         *
         *	On a 1990 paper the rto value is changed to:
         *	RTO = rtt + 4 * mdev
         *
         * Funny. This algorithm seems to be very broken.
         * These formulae increase RTO, when it should be decreased, increase
         * too slowly, when it should be increased quickly, decrease too quickly
         * etc. I guess in BSD RTO takes ONE value, so that it is absolutely
         * does not matter how to _calculate_ it. Seems, it was trap
         * that VJ failed to avoid. 8)
         */
        if (srtt != 0) {
            m -= (srtt >> 3);    /* m is now error in rtt est */
            srtt += m;        /* rtt = 7/8 rtt + 1/8 new */
            if (m < 0) {
                m = -m;        /* m is now abs(error) */
                m -= (sk.mdev_us >> 2);   /* similar update on mdev */
                /* This is similar to one of Eifel findings.
                 * Eifel blocks mdev updates when rtt decreases.
                 * This solution is a bit different: we use finer gain
                 * for mdev in this case (alpha*beta).
                 * Like Eifel it also prevents growth of rto,
                 * but also it limits too fast rto decreases,
                 * happening in pure Eifel.
                 */
                if (m > 0) {
                    m >>= 3;
                }
            } else {
                m -= (sk.mdev_us >> 2);   /* similar update on mdev */
            }
            sk.mdev_us += m;        /* mdev = 3/4 mdev + 1/4 new */
            if (sk.mdev_us > sk.mdev_max_us) {
                sk.mdev_max_us = sk.mdev_us;
                if (sk.mdev_max_us > sk.rttvar_us) {
                    sk.rttvar_us = sk.mdev_max_us;
                }
            }
            if (after(sk.snd_una, sk.rtt_seq)) {
                if (sk.mdev_max_us < sk.rttvar_us) {
                    sk.rttvar_us -= (sk.rttvar_us - sk.mdev_max_us) >> 2;
                }
                sk.rtt_seq = sk.snd_nxt;
                sk.mdev_max_us = tcp_rto_min_us(sk);

                // tcp_bpf_rtt(sk, mrtt_us, srtt);
            }
        } else {
            /* no previous measure. */
            srtt = m << 3;        /* take the measured time to be rtt */
            sk.mdev_us = m << 1;    /* make sure rto = 3*rtt */
            sk.rttvar_us = Math.max(sk.mdev_us, tcp_rto_min_us(sk));
            sk.mdev_max_us = sk.rttvar_us;
            sk.rtt_seq = sk.snd_nxt;

            // tcp_bpf_rtt(sk, mrtt_us, srtt);
        }
        sk.srtt_us = Math.max(1, srtt);
        log.trace("[RTT] Compute a smoothed rtt: {}us", sk.srtt_us >> 3);
    }

    /**
     * Calculate rto without backoff.  This is the second half of Van Jacobson's
     * routine referred to above.
     *
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_input.c#L980">tcp_set_rto</a>
     */
    private void tcp_set_rto(final TcpSock sk) {
        /* Old crap is replaced with new one. 8)
         *
         * More seriously:
         * 1. If rtt variance happened to be less 50msec, it is hallucination.
         *    It cannot be less due to utterly erratic ACK generation made
         *    at least by solaris and freebsd. "Erratic ACKs" has _nothing_
         *    to do with delayed acks, because at cwnd>2 true delack timeout
         *    is invisible. Actually, Linux-2.4 also generates erratic
         *    ACKs in some circumstances.
         */
        sk.icsk_rto = (int) __tcp_set_rto(sk);

        /* 2. Fixups made earlier cannot be right.
         *    If we do not estimate RTO correctly without them,
         *    all the algo is pure shit and should be replaced
         *    with correct one. It is exactly, which we pretend to do.
         */

        /* NOTE: clamping at TCP_RTO_MIN is not required, current algo
         * guarantees that rto is higher.
         */
        tcp_bound_rto(sk);
        log.trace("[RTO] Set retransmission timeout: {}ms", sk.icsk_rto);
    }

    /**
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_input.c#L1001">tcp_init_cwnd</a>
     */
    private int tcp_init_cwnd(final TcpSock sk) {
        // __u32 cwnd = (dst ? dst_metric(dst, RTAX_INITCWND) : 0);
        int cwnd = 0;

        if (0 == cwnd) {
            cwnd = TcpConstants.TCP_INIT_CWND;
        }
        return Math.min(cwnd, sk.snd_cwnd_clamp);
    }

    // ...

    // https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_input.c#L2190
    public void tcp_enter_loss() {

    }

    // https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_input.c#L3186
    private static void tcp_update_rtt_min(final TcpSock sk, final long rtt_us, final int flag) {
        // int wlen = READ_ONCE(sock_net(sk)->ipv4.sysctl_tcp_min_rtt_wlen) * HZ;
        int wlen = 1 * HZ;

//        if ((flag & FLAG_ACK_MAYBE_DELAYED) && rtt_us > tcp_min_rtt()) {
        /* If the remote keeps returning delayed ACKs, eventually
         * the min filter would pick it up and overestimate the
         * prop. delay when it expires. Skip suspected delayed ACKs.
         */
//            return;
//        }

        // FIXME
        // minmax_running_min(rtt_min, wlen, tcp_jiffies32(), 0 != rtt_us ? rtt_us : jiffies_to_usecs(1));
    }

    // https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_input.c#L3202
    private boolean tcp_ack_update_rtt(final TcpSock sk,
                                       final int flag, long seq_rtt_us,
                                       final long sack_rtt_us, final long ca_rtt_us/*,
                                       struct rate_sample *rs*/) {

        /* Prefer RTT measured from ACK's timing to TS-ECR. This is because
         * broken middle-boxes or peers may corrupt TS-ECR fields. But
         * Karn's algorithm forbids taking RTT if some retransmitted data
         * is acked (RFC6298).
         */
        if (seq_rtt_us < 0) {
            seq_rtt_us = sack_rtt_us;
        }

        /* RTTM Rule: A TSecr value received in a segment is used to
         * update the averaged RTT measurement only if the segment
         * acknowledges some new data, i.e., only if it advances the
         * left edge of the send window.
         * See draft-ietf-tcplw-high-performance-00, section 3.3.
         */
//        if (seq_rtt_us < 0 && tp->rx_opt.saw_tstamp && tp->rx_opt.rcv_tsecr && flag & FLAG_ACKED)
//            seq_rtt_us = ca_rtt_us = tcp_rtt_tsopt_us(tp);

        // rs->rtt_us = ca_rtt_us; /* RTT of last (S)ACKed packet (or -1) */
        if (seq_rtt_us < 0) {
            return false;
        }

        /* ca_rtt_us >= 0 is counting on the invariant that ca_rtt_us is
         * always taken together with ACK, SACK, or TS-opts. Any negative
         * values will be skipped with the seq_rtt_us < 0 check above.
         */
        tcp_update_rtt_min(sk, ca_rtt_us, flag);
        tcp_rtt_estimator(sk, seq_rtt_us);

        // 116.228.111.118 180.168.255.18
        // TODO OPEN ME
        tcp_set_rto(sk);

        /* RFC6298: only reset backoff on valid RTT measurement. */
        sk.icsk_backoff = 0;
        return true;
    }

    // ...

    /**
     * Restart timer after forward progress on connection.
     * RFC2988 recommends to restart timer to now+rto.
     *
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_input.c#L3147">tcp_rearm_rto</a>
     */
    public static void tcp_rearm_rto(final TcpSock sk, TcpTimer timer) {

        // ...

        if (sk.packets_out <= 0) {
            sk.inet_csk_clear_xmit_timer(timer, ICSK_TIME_RETRANS);
        } else {
            int rto = sk.icsk_rto;

            /* Offset the time elapsed after installing regular RTO */
            if (sk.icsk_pending == ICSK_TIME_REO_TIMEOUT
                    || sk.icsk_pending == ICSK_TIME_LOSS_PROBE) {
                final long delta_us = sk.tcp_rto_delta_us();
                /* delta_us may not be positive if the socket is locked
                 * when the retrans timer fires and is rescheduled.
                 */
                rto = (int) usecs_to_jiffies(Math.max(delta_us, 1));
            }
            sk.tcp_reset_xmit_timer(timer, ICSK_TIME_RETRANS, rto, true);
        }
    }

    private int tcp_clean_rtx_queue(final TcpSock tp, final int prior_snd_una) {
        // https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_input.c#L3340

        long first_ackt = 0;
        int first_ackseq = 0;
        long last_ackt = 0;
        int flag = 0;
        boolean fully_acked = true;
        long seq_rtt_us = 0;
        long ca_rtt_us = 0;

        TcpBuffer skb;
        while (null != (skb = tp.tcp_rtx_queue.peek())) {
            final int seq = skb.sequenceNumber();
            final int end_seq = determineEndSeq(skb);
            int acked_pcount = 1;
            int sacked = skb.sacked;

            /* Determine how many packets and what bytes were acked, tso and else */
            if (after(end_seq, tp.snd_una)) {
                if (tp.tcp_skb_pcount(skb) == 1 || !after(tp.snd_una, seq)) {
                    break;
                }

                // FIXME
                acked_pcount = 1;
                // acked_pcount = tcp_tso_acked(sk, skb);
                //			if (!acked_pcount)
                //				break;
                fully_acked = false;
            } else {
                acked_pcount = tp.tcp_skb_pcount(skb);
            }

            /*-
             * 如果是重传过的包.
             */
            if (0 != (sacked & TCPCB_RETRANS)) {
                // 已发出去的重传.
                if (0 != (sacked & TCPCB_SACKED_RETRANS)) {
                    tp.retrans_out -= acked_pcount;
                }

                flag |= FLAG_RETRANS_DATA_ACKED;
            } else if (0 == (sacked & TCPCB_SACKED_ACKED)) {
                /*-
                 * 不是重传过的包且不是被 SACKED 过的.
                 */
                last_ackt = tp.tcp_skb_timestamp_us(skb);
                if (0 == first_ackt) {
                    first_ackt = last_ackt;
                    first_ackseq = skb.sequenceNumber();
                }
            }


            tp.packets_out -= acked_pcount;

            /*
            if (!th.getSyn()) {
                flag |= FLAG_DATA_ACKED;
            } else {
                flag |= FLAG_SYN_ACKED;
                retrans_stamp = 0;
            }
            */


            tp.tcp_rtx_queue.remove(skb);

            tp.tcp_ack_tstamp();
        }

        if (between(tp.snd_up, prior_snd_una, tp.snd_una)) {
            tp.snd_up = tp.snd_una;
        }

        if (first_ackt != 0 && (0 == (flag & FLAG_RETRANS_DATA_ACKED))) {
            /*-
             * 有开始时间, 且不是重传数据的ACK.
             */
            seq_rtt_us = tcp_stamp_us_delta(tp.tcp_mstamp, first_ackt);
            ca_rtt_us = tcp_stamp_us_delta(tp.tcp_mstamp, last_ackt);

//            tp.logTrace("[RTT] Seq {} round-trip-time: {}us", first_ackseq, seq_rtt_us);
        }

        /*-
         * 更新 RTT, RTO.
         */

        // FIXME
        tcp_ack_update_rtt(tp, flag, seq_rtt_us, 0, ca_rtt_us);

        return 0;
    }

    /**
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_input.c#L3546">tcp_ack_probe</a>
     */
    private void tcp_ack_probe(final TcpSock tp) {
        final TcpBuffer head = tp.tcp_send_head();

        /* Was it a usable window open? */
        if (null == head) {
            return;
        }
        if (!after(determineEndSeq(head), tp.tcp_wnd_end())) {
            tp.icsk_backoff = 0;
            tp.icsk_probes_tstamp = 0;
            tp.inet_csk_clear_xmit_timer(demultiplexer.timer, ICSK_TIME_PROBE0);
            /* Socket must be waked up by subsequent tcp_data_snd_check().
             * This function is not for random using!
             */
        } else {
            long when = tp.tcp_probe0_when(TCP_RTO_MAX);
            when = tp.tcp_clamp_probe0_to_user_timeout(tp, when);
            tp.tcp_reset_xmit_timer(demultiplexer.timer, ICSK_TIME_PROBE0, when, true);
        }
    }

    /**
     * @param ack
     * @param ack_seq
     * @param nwin
     * @return
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_input.c#L3620">tcp_may_update_window</a>
     */
    private boolean tcp_may_update_window(final TcpSock tp, final int ack, final int ack_seq, final int nwin) {
        return ack > tp.snd_una
                || ack_seq > tp.snd_wl1
                || (ack_seq == tp.snd_wl1 && (nwin > tp.snd_wnd || nwin == 0));
    }

    /**
     * @param ack
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_input.c#L3629">tcp_snd_sne_update</a>
     */
    private void tcp_snd_sne_update(TcpSock tp, int ack) {

    }

    /**
     * If we update tp->snd_una, also update tp->bytes_acked.
     *
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_input.c#L3647">tcp_snd_una_update</a>
     */
    private void tcp_snd_una_update(final TcpSock tp, final int ack) {
        final int delta = ack - tp.snd_una;
        tp.bytes_acked += delta;
        tcp_snd_sne_update(tp, ack);
        tp.snd_una = ack;
    }

    private void tcp_rcv_nxt_update(final TcpSock tp, final int seq) {
        final int delta = seq - tp.rcv_nxt;
        tp.bytes_received += delta;
        // tcp_rcv_sne_update(seq)
        tp.rcv_nxt = seq;
    }

    /**
     * Update our send window.
     * <p>
     * Window update algorithm, described in RFC793/RFC1122 (used in linux-2.2
     * and in FreeBSD. NetBSD's one is even worse.) is wrong.
     *
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_input.c#L3696">tcp_ack_update_window</a>
     */
    private int tcp_ack_update_window(final TcpSock tp,
                                      final IpPacket ipPacket, final TcpPacket tcpPacket,
                                      final int ack, final int ack_seq) {
        final TcpPacket.TcpHeader tcpHdr = tcpPacket.getHeader();
        int flag = 0;
        int nwin = tcpHdr.getWindowAsInt();

        if (!tcpHdr.getSyn()) {
            nwin <<= tp.rx_opt.snd_wscale;
        }

        /*-
         * 如果允许改ACK更新窗口.
         */
        if (tcp_may_update_window(tp, ack, ack_seq, nwin)) {
            flag |= FLAG_WIN_UPDATE;
            tp.tcp_update_wl(ack_seq);

            if (nwin != tp.snd_wnd) {
//                log.warn("[Window Update] {} -> {}", snd_wnd, nwin);
                tp.snd_wnd = nwin;

                /* Note, it is the only place, where
                 * fast path is recovered for sending TCP.
                 * TODO
                 */
//                tp->pred_flags = 0;
//                tcp_fast_path_check(sk);

                if (nwin > tp.max_window) {
                    tp.max_window = nwin;
                    output.tcp_sync_mss(tp, tp.icsk_pmtu_cookie);
                }
            }
        }

        tcp_snd_una_update(tp, ack);

        /*-
         * 慢启动阶段(slow-start phase): cwnd = cwnd + 1 (SMSS)
         * 拥塞避免阶段(congestion-avoidance phase): cwnd = cwnd + 1 (SMSS) / cwnd
         */
        // cwnd = cwnd < ssthresh ? cwnd + sndMss : cwnd + sndMss / cwnd;
        return flag;
    }

    /**
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_input.c#L3606"></a>
     */
    private boolean __tcp_oow_rate_limited(final TcpSock net/*FIXME*/, int mib_idx, long last_oow_ack_time) {
//        final long last_oow_ack_time = net.last_oow_ack_time;
        if (0 != last_oow_ack_time) {
            final long elapsed = tcp_jiffies32() - last_oow_ack_time;
            if (0 <= elapsed && elapsed < net.ipv4_sysctl_tcp_invalid_ratelimit) {
                return true;/* rate-limited: don't send yet! */
            }
        }

        net.last_oow_ack_time = tcp_jiffies32();

        return false;    /* not rate-limited: go ahead, send dupack now! */
    }

    /* Return true if we're currently rate-limiting out-of-window ACKs and
     * thus shouldn't send a dupack right now. We rate-limit dupacks in
     * response to out-of-window SYNs or ACKs to mitigate ACK loops or DoS
     * attacks that send repeated SYNs or ACKs for the same connection. To
     * do this, we do not send a duplicate SYNACK or ACK if the remote
     * endpoint is sending out-of-window SYNs or pure ACKs at a high rate.
     */
    private boolean tcp_oow_rate_limited(final TcpSock net/* FIXME */,
                                         final IpPacket ipPacket, final TcpPacket tcpPacket,
                                         final int mib_idx, final long last_oow_ack_time) {
        final TcpPacket.TcpHeader th = tcpPacket.getHeader();
        /* Data packets without SYNs are not likely part of an ACK loop. */
        if ((th.getSequenceNumber() != determineEndSeq(tcpPacket)) && !th.getSyn()) {
            return false;
        }
        return __tcp_oow_rate_limited(net, mib_idx, last_oow_ack_time);
    }

    /**
     * RFC 5961 7 [ACK Throttling]
     *
     * @param tp
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_input.c#L3649">tcp_send_challenge_ack</a>
     */
    private void tcp_send_challenge_ack(final Channel net, final TcpSock tp /*FIXME*/) {
        /* First check our per-socket dupack rate limit. */
        if (__tcp_oow_rate_limited(tp, 0, tp.last_oow_ack_time)) {
            return;
        }

        int ack_limit = tp.ipv4_sysctl_tcp_challenge_ack_limit;
        if (ack_limit == Integer.MAX_VALUE) {
            output.tcp_send_ack(net, tp);
            return;
        }

        /* Then check host-wide RFC 5961 rate limit. */
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
    }

    private void tcp_in_ack_event(final TcpSock sk, int ack_env_flags) {

    }


    /**
     * This routine deals with incoming acks, but not outgoing ones.
     *
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_input.c#L3805">tcp_ack</a>
     */
    private int tcp_ack(final Channel net, final TcpSock tp, final IpPacket ipPacket, final TcpPacket tcpPacket, int flag) {
        final TcpPacket.TcpHeader tcpHdr = tcpPacket.getHeader();
        final int prior_snd_una = tp.snd_una;
        final int prior_packets_out = tp.packets_out;
        final int ack_seq = tcpHdr.getSequenceNumber();
        final int ack = tcpHdr.getAcknowledgmentNumber();

        /*-
         * If the ack is older than previous acks then we can probably ignore it.
         */
        if (before(ack, prior_snd_una)) {
            /* do not accept ACK for bytes we never sent. */
            final int max_window = Math.min(tp.max_window, tp.bytes_acked);

            /* RFC 5961 5.2 [Blind Data Injection Attack].[Mitigation] */
            if (before(ack, prior_snd_una - max_window)) {
                if (0 == (flag & FLAG_NO_CHALLENGE_ACK)) {
                    tcp_send_challenge_ack(net, tp);
                }
                log.warn("TOO OLD ACK on {}: ACK({}) < SND.UNA({}), {}", tp.state(), ack, prior_snd_una, tcpHdr);
                return -TcpDropReason.SKB_DROP_REASON_TCP_TOO_OLD_ACK;
            }
            // goto old_ack.
            log.warn("OLD ACK on {}: ACK({}) < SND.UNA({}), {}", tp.state(), ack, prior_snd_una, tcpHdr);
            return 0;
        }

        /*-
         * If the ack includes data we haven't sent yet, discard this segment (RFC793 Section 3.9).
         */
        if (after(ack, tp.snd_nxt)) {
            return -TcpDropReason.SKB_DROP_REASON_TCP_ACK_UNSENT_DATA;
        }

        /*-
         * If the ack is newer ack then we should reset retransmit counter.
         */
        if (after(ack, prior_snd_una)) {
            flag |= FLAG_SND_UNA_ADVANCED;
            tp.icsk_retransmits = 0;
        }

//        prior_fack = tcp_is_sack(tp) ? tcp_highest_sack_seq(tp) : tp.snd_una;
//        rs.prior_in_flight = tp.tcp_packets_in_flight();

        /* ts_recent update must be made after we are sure that the packet
         * is in window.
         */
        if (0 != (flag & FLAG_UPDATE_TS_RECENT)) {
//            flag |= tcp_replace_ts_recent(tp, tcpHdr.getSequenceNumber());
        }

        if ((flag & (FLAG_SLOWPATH | FLAG_SND_UNA_ADVANCED)) == FLAG_SND_UNA_ADVANCED) {
            /*-
             * Window is constant, pure forward advance.
             * No more checks are required.
             * Note, we use the fact that SND.UNA>=SND.WL2.
             */
            tp.tcp_update_wl(ack_seq);
            tcp_snd_una_update(tp, ack);
            flag |= FLAG_WIN_UPDATE;
        } else {
//            int ack_ev_flags = CA_ACK_SLOWPATH;
            if (ack_seq != determineEndSeq(tcpPacket)) {
                flag |= FLAG_DATA;
            }

            flag |= tcp_ack_update_window(tp, ipPacket, tcpPacket, ack, ack_seq);

//            if (skb.sacked) {
//                flag |= tcp_sacktag_write_queue(sk, skb, prior_snd_una, &sack_state);
//            }

//            if (sack_state.sack_delivered) {
//                tcp_count_delivered(tp, sack_state.sack_delivered, flag & FLAG_ECE);
//            }

        }

        /*-
         * We passed data and got it acked, remove any soft error log. Something worked...
         */
        tp.sk_err_soft = 0;
        tp.icsk_probes_out = 0;
        tp.rcv_tstamp = tcp_jiffies32();

        if (prior_packets_out == 0) {
            // goto no_queue.
            tcp_in_ack_event(tp, flag);

            /*-
             * If this ack opens up a zero window, clear backoff.  It was
             * being used to time the probes, and is probably far higher than
             * it needs to be for normal retransmission.
             */
            tcp_ack_probe(tp);
            return 1;
        }

        // See if we can take anything off of the retransmit queue.
        flag |= tcp_clean_rtx_queue(tp, prior_snd_una);

        // tcp_rack_update_reo_wnd(sk, &rs);

        tcp_in_ack_event(tp, flag);

//        if (tp->tlp_high_seq)
//            tcp_process_tlp_ack(sk, ack, flag);
        // TODO ...

        // tcp_cong_control();
        // tcp_xmit_recovery

        return 1;
    }

    /**
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_input.c#L4394">tcp_sequence</a>
     */
    private int tcp_sequence(final TcpSock tp, final int seq, final int end_seq) {
        if (before(end_seq, tp.rcv_wup)) {
            return SKB_DROP_REASON_TCP_OLD_SEQUENCE;
        }
        if (after(end_seq, tp.rcv_nxt + output.tcp_receive_window(tp))) {
            if (after(seq, tp.rcv_nxt + output.tcp_receive_window(tp))) {
                return SKB_DROP_REASON_TCP_INVALID_SEQUENCE;
            }

            /* Only accept this packet if receive queue is empty. */
//            if (skb_queue_len(&sk->sk_receive_queue)){
//                return SKB_DROP_REASON_TCP_INVALID_END_SEQUENCE;
//            }
        }
        return SKB_NOT_DROPPED_YET;
    }

    private int tcp_disordered_ack_check(final TcpSock tp, final IpPacket ipPacket, final TcpPacket tcpPacket) {
        int reason = TCP_RFC7323_PAWS;
        TcpPacket.TcpHeader th = tcpPacket.getHeader();
        int seq = th.getSequenceNumber();
        int ack = th.getAcknowledgmentNumber();

        /* 1. Is this not a pure ACK ? */
        if (!th.getAck() || seq != determineEndSeq(tcpPacket)) {
            return reason;
        }

        /* 2. Is its sequence not the expected one ? */
        if (seq != tp.rcv_nxt) {
            return before(seq, tp.rcv_nxt) ? SKB_DROP_REASON_TCP_RFC7323_PAWS_ACK : reason;
        }

        /* 3. Is this not a duplicate ACK ? */
        if (ack != tp.snd_una) {
            return reason;
        }

        /* 4. Is this updating the window ? */
//        if (tcp_may_update_window(tp, ack, seq, th.getWindowAsInt() << tp.rx_opt.snd_wscale)) {
//            return reason;
//        }
        /* 5. Is this not in the replay window ? */
//        if ((s32)(tp->rx_opt.ts_recent - tp->rx_opt.rcv_tsval) > tcp_tsval_replay(sk)) {
//            return reason;
//        }
        return 0;
    }


    // https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_input.c#L4521
    public void tcp_done_with_error(final TcpSock tp, final int err) {
        // sk->sk_err = err;
        // logError("TCP DONE WITH ERROR: {}", err);

        // tcp_write_queue_purge(sk);
        // https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_input.c#L4515
        demultiplexer.tcp_done(tp);

        // if (!sock_flag(sk, SOCK_DEAD))
        //    sk_error_report(sk);
    }

    /**
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_input.c#L4430">tcp_reset</a>
     */
    private void tcp_reset(final TcpSock tp, final IpPacket ipPacket, final TcpPacket tcpPacket) {
        // sk_is_mptcp

        int err;

        /* We want the right error as BSD sees it (and indeed as we do). */
        switch (tp.state()) {
            case TCP_SYN_SENT:
                err = ECONNREFUSED;
                break;
            case TCP_CLOSE_WAIT:
                err = EPIPE;
                break;
            case TCP_CLOSE:
                return;
            default:
                err = ECONNRESET;
        }
        tcp_done_with_error(tp, err);
    }

    /**
     * Process the FIN bit.
     */
    private void tcp_fin(final Channel net, final TcpSock tp) {
        inet_csk_schedule_ack(tp);

        tp.sk_shutdown |= RCV_SHUTDOWN;
        // sock_set_flag(sk, SOCK_DONE)

        final TcpState state = tp.state();
        switch (state) {
            case TCP_SYN_RECV:
            case TCP_ESTABLISHED:
                /* Move to CLOSE_WAIT */
                tp.state(TcpState.TCP_CLOSE_WAIT);
                tp.inet_csk_enter_pingpong_mode();
                break;
            case TCP_CLOSE_WAIT:
            case TCP_CLOSING:
                /* Received a retransmission of the FIN, do nothing. */
                break;
            case TCP_LAST_ACK:
                /* RFC793: Remain in the LAST-ACK state. */
                break;
            case TCP_FIN_WAIT1:
                /*-
                 * This case occurs when a simultaneous close
                 * happens, we must ack the received FIN and
                 * enter the CLOSING state.
                 */
                output.tcp_send_ack(net, tp);
                tp.state(TcpState.TCP_CLOSING);
                break;
            case TCP_FIN_WAIT2:
                /* Received a FIN -- send ACK and enter TIME_WAIT. */
                output.tcp_send_ack(net, tp);
                demultiplexer.tcp_time_wait(tp, TcpState.TCP_TIME_WAIT, 0);
                break;
            default:
                /* Only TCP_LISTEN and TCP_CLOSE are left, in these
                 * cases we should never reach this piece of code.
                 */
                log.error("tcp_fin(): Impossible, sk->sk_state={}", state);
                break;
        }

        /* It _is_ possible, that we have something out-of-order _after_ FIN.
         * Probably, we should reset in this case. For now drop them.
         */
        // skb_rbtree_purge(&tp->out_of_order_queue);
//        if (tcp_is_sack(tp)) {
//            tcp_sack_reset(&tp->rx_opt)
//        }

//        if (!sock_flag(sk, SOCK_DEAD)) {
        // sk->sk_state_change(sk)
        /* Do not send POLL_HUP for half duplex close. */
//            if (sk->sk_shutdown == SHUTDOWN_MASK ||
//                    sk->sk_state == TCP_CLOSE)
//                sk_wake_async(sk, SOCK_WAKE_WAITD, POLL_HUP);
//            else
//                sk_wake_async(sk, SOCK_WAKE_WAITD, POLL_IN);
//        }
    }

    private void tcp_send_dupack(final Channel net, final TcpSock tp, final TcpPacket skb) {
        TcpPacket.TcpHeader th = skb.getHeader();
        int seq = th.getSequenceNumber();
        int end_seq = determineEndSeq(skb);
        if (end_seq != seq && before(seq, tp.rcv_nxt)) {
            tcp_enter_quickack_mode(tp, TCP_MAX_QUICKACKS);

            // if tcp_is_sack ...
        }

        output.tcp_send_ack(net, tp);
    }

    public void tcp_sack_compress_send_ack(TcpSock tp) {
        // FIXME
    }

    private void tcp_data_queue_ofo(final TcpSock sk, final IpPacket ipPacket, final TcpPacket skb) {

    }

    private int tcp_queue_rcv(final TcpSock tp, final TcpPacket skb) {
        // https://www.cnblogs.com/wanpengcoder/p/11752122.html
        final TcpPacket.TcpHeader hdr = skb.getHeader();
        final int len = skb.length() - hdr.length();
        if (len > 0) {
            demultiplexer.consume(tp, skb);
        }
        tcp_rcv_nxt_update(tp, determineEndSeq(skb));
        return len;
    }

    /**
     * @param tcpPacket
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_input.c#L5229">tcp_input.c</a>
     */
    public void tcp_data_queue(final Channel net, final TcpSock tp, final IpPacket ipPacket, final TcpPacket tcpPacket) throws IOException {
        final TcpPacket.TcpHeader hdr = tcpPacket.getHeader();
        final int seq = hdr.getSequenceNumber();
        final int endSeq = determineEndSeq(tcpPacket);

        if (seq == endSeq) {
            return;
        }

        /*-
         * Queue data for delivery to the user.
         * Packets in sequence go to the receive queue.
         * Out of sequence packets to the out_of_order_queue.
         */
        if (seq == tp.rcv_nxt) {
            if (output.tcp_receive_window(tp) == 0) {
                /*-
                 * Some stacks are known to send bare FIN packets
                 * in a loop even if we send RWIN 0 in our ACK.
                 * Accepting this FIN does not hurt memory pressure
                 * because the FIN flag will simply be merged to the
                 * receive queue tail skb in most cases.
                 */
                final int len = tcpPacket.length() - hdr.length();
                if (len > 0 && hdr.getFin()) {
                    queue_and_out(net, tp, ipPacket, tcpPacket);
                } else {
                    out_of_window(tp, ipPacket, tcpPacket, TcpDropReason.SKB_DROP_REASON_TCP_ZEROWINDOW);
                    return;
                }
            }

            /* Ok. In sequence. In window. */
            queue_and_out(net, tp, ipPacket, tcpPacket);
        } else if (!after(endSeq, tp.rcv_nxt)) {
            tcp_rcv_spurious_retrans(tcpPacket);
            /* A retransmit, 2nd most common case.  Force an immediate ack. */
            // ... tcp_dsack_set ....
            out_of_window(tp, ipPacket, tcpPacket, TcpDropReason.SKB_DROP_REASON_TCP_OLD_DATA);
        } else if (!before(seq, tp.rcv_nxt + output.tcp_receive_window(tp))) {
            /* Out of window. F.e. zero window probe. */
            out_of_window(tp, ipPacket, tcpPacket, TcpDropReason.SKB_DROP_REASON_TCP_OVERWINDOW);
        } else if (before(seq, tp.rcv_nxt)) {
            /* Partial packet, seq < rcv_next < end_seq */
            // tcp_dasck_set ...

            /*-
             * If window is closed, drop tail of packet. But after
             * remembering D-SACK for its head made in previous line.
             */
            if (output.tcp_receive_window(tp) == 0) {
                out_of_window(tp, ipPacket, tcpPacket, TcpDropReason.SKB_DROP_REASON_TCP_ZEROWINDOW);
            } else {
                // goto queue_and_out
                queue_and_out(net, tp, ipPacket, tcpPacket);
            }
        } else {
            tcp_data_queue_ofo(tp, ipPacket, tcpPacket);
        }
    }

    private void out_of_window(TcpSock tp, final IpPacket ipPacket, final TcpPacket tcpPacket, final int reason) {
        tcp_enter_quickack_mode(tp, TCP_MAX_QUICKACKS);
        inet_csk_schedule_ack(tp);
        drop(tp, ipPacket, tcpPacket, reason);
    }

    private void drop(final TcpSock tp, final IpPacket ipPacket, final TcpPacket tcpPacket, final int reason) {
        // tcp_drop_reason(sk, skb, reason);
    }

    private void queue_and_out(final Channel net, final TcpSock tp, final IpPacket ipPacket, final TcpPacket tcpPacket) throws IOException {
        // queue_and_out;
        final TcpPacket.TcpHeader th = tcpPacket.getHeader();

        // tcp_try_remem_schedule ...

        int eaten = tcp_queue_rcv(tp, tcpPacket/*, &fragstolen*/);

        final int len = tcpPacket.length() - th.length();
        if (len > 0) {
            tcp_event_data_recv(tp, ipPacket, tcpPacket);
        }

        if (th.getFin()) {
            tcp_fin(net, tp);
        }

        // TODO ...
        // if (!RB_EMPTY_ROOT(&tp->out_of_order_queue)) {
        //    tcp_ofo_queue(sk);
        // ...
        // }
//        if (tp->rx_opt.num_sacks)
//            tcp_sack_remove(tp)

        // tcp_fast_path_check(sk)

//        if (eaten > 0) {
//            kfree_skb_partial(skb, fragstolen)
//        }
//        if (!sock_flag(sk, SOCK_DEAD))
//            tcp_data_ready(sk);
    }


    // https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_input.c#L5739
    private void tcp_check_space(final TcpSock sk) {
        // FIXME
    }

    /**
     * Check if sending an ack is needed.
     * <p>
     * https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_input.c#L5760.
     */
    private void __tcp_ack_snd_check(final Channel net, final TcpSock sk) {
        if (
            // (tp.rcv_nxt - tp.rcv_wup > tp.icsk_ack.rcv_mss
            /* ... and right edge of window advances far enough.
             * (tcp_recvmsg() will send ACK otherwise).
             * If application uses SO_RCVLOWAT, we want send ack now if
             * we have not received enough bytes to satisfy the condition.
             */
            // && (tp.rcv_nxt - tp.copied_seq < tp.sk_rcvlowat || tp.output.__tcp_select_window(tp) >= tp.rcv_wnd)
            // ) ||
                tcp_in_quickack_mode(sk) || 0 != (sk.icsk_ack.pending & ICSK_ACK_NOW)) {
            output.tcp_send_ack(net, sk);
            return;
        }

        output.tcp_send_delayed_ack(net, sk);

        // ...
    }

    /**
     * Check if sending an ack is needed.
     *
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_input.c#L5827">tcp_ack_snd_check</a>
     */
    public void tcp_ack_snd_check(final Channel net, TcpSock tp) {
        if (!tp.inet_csk_ack_scheduled()) {
            /* We sent a data segment already. */
            return;
        }
        __tcp_ack_snd_check(net, tp);
    }

    private boolean tcp_reset_check(final TcpSock tp, final IpPacket ipPacket, final TcpPacket tcpPacket) {
        // https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_input.c#L5939
        final int seq = tcpPacket.getHeader().getSequenceNumber();
        return seq == tp.rcv_nxt - 1 && 0 != ((1 << tp.state().ordinal()) | (TCPF_CLOSE_WAIT | TCPF_LAST_ACK | TCPF_CLOSING));
    }


    void tcp_rcv_spurious_retrans(final TcpPacket skb) {
    }


    public long tcp_stamp_us_delta(long t1, long t0) {
        return Math.max(t1 - t0, 0);
    }


    /**
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_input.c#L5664">tcp_data_snd_check</a>
     */
    protected void tcp_data_snd_check(final Channel net, final TcpSock tp) {
        demultiplexer.tcp_push_pending_frames(net, tp);
        tcp_check_space(tp);
    }

    /**
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_input.c#L5870">tcp_validate_incoming</a>
     */
    private boolean tcp_validate_incoming(final Channel net, final TcpSock tp, final IpPacket ipPacket, final TcpPacket tcpPacket) {
        final TcpPacket.TcpHeader th = tcpPacket.getHeader();
        final int seq = th.getSequenceNumber();
        final int end_seq = determineEndSeq(tcpPacket);
        final int ack = th.getAcknowledgmentNumber();

        int reason = 0; //tcp_disordered_ack_check(tp, skb);
        if (0 == reason) {
            // goto step1;
        }

        /* Reset is accepted even if it did not pass PAWS. */
        if (0 == reason || th.getRst()) {
            // goto step1
        } else if (th.getSyn()) {
            // goto syn_challenge
            tcp_send_challenge_ack(net, tp);
            reason = SKB_DROP_REASON_TCP_INVALID_SYN;
//            goto discard;
            tcp_drop_reason(tp, reason);
            return false;
        } else if (reason == SKB_DROP_REASON_TCP_RFC7323_PAWS_ACK) {
            // goto discard;
            tcp_drop_reason(tp, reason);
            return false;
        } else if (!tcp_oow_rate_limited(tp, ipPacket, tcpPacket, 0, tp.last_oow_ack_time)) {
            tcp_send_dupack(net, tp, tcpPacket);
            // goto dicard
            tcp_drop_reason(tp, reason);
            return false;
        }

        step1:
        // Step 1: check sequence number.
        reason = tcp_sequence(tp, seq, end_seq);
        if (0 != reason) {
            /* RFC793, page 37: "In all states except SYN-SENT, all reset
             * (RST) segments are validated by checking their SEQ-fields."
             * And page 69: "If an incoming segment is not acceptable,
             * an acknowledgment should be sent in reply (unless the RST
             * bit is set, if so drop the segment and return)".
             */
            if (!th.getRst()) {
                if (th.getSyn()) {
                    // goto syn_challenge;
                    tcp_send_challenge_ack(net, tp);
                    reason = SKB_DROP_REASON_TCP_RESET;
                    // goto discard;
                    tcp_drop_reason(tp, reason);
                    return false;
                }

                if (reason == SKB_DROP_REASON_TCP_INVALID_SEQUENCE
                        || reason == SKB_DROP_REASON_TCP_INVALID_END_SEQUENCE) {
                    // stats
                }

                if (!tcp_oow_rate_limited(tp, ipPacket, tcpPacket, 1, tp.last_oow_ack_time)) {
                    tcp_send_dupack(net, tp, tcpPacket);
                }
            } else if (tcp_reset_check(tp, ipPacket, tcpPacket)) {
                // goto reset
                tcp_reset(tp, ipPacket, tcpPacket);
                return false;
            }
            // goto discard
            tcp_drop_reason(tp, reason);
            return false;
        }

        /*-
         * Step 2: check RST bit.
         */
        if (th.getRst()) {
            /*-
             * RFC 5961 3.2 (extend to match against (RCV.NXT - 1) after a  FIN and SACK too if available):
             * If seq num matches RCV.NXT or (RCV.NXT - 1) after a FIN, or the right-most SACK block,
             * then
             *     RESET the connection
             * else
             *     Send a challenge ACK
             */
            if (th.getSequenceNumber() == tp.rcv_nxt || tcp_reset_check(tp, ipPacket, tcpPacket)) {
                // goto reset
                tcp_reset(tp, ipPacket, tcpPacket);
                return false;
            }

            // if tcp_is_sack ...

            /* Disable TFO if RST is out-of-order
             * and no data has been received
             * for current active TFO socket
             */
//            if (tp->syn_fastopen && !tp->data_segs_in &&
//                    sk->sk_state == TCP_ESTABLISHED)
//                tcp_fastopen_active_disable(sk);

            tcp_send_challenge_ack(net, tp);
            reason = SKB_DROP_REASON_TCP_RESET;
//            goto discard;
            tcp_drop_reason(tp, reason);
            return false;
        }

        /* step 3: check security and precedence [ignored] */

        /* step 4: Check for a SYN
         * RFC 5961 4.2 : Send a challenge ack
         */
        if (th.getSyn()) {
            TcpState tcpState = tp.state();
            if (TCP_SYN_RECV.equals(tcpState)
                    && th.getAck()
                    && seq + 1 == end_seq
                    && seq + 1 == tp.rcv_nxt
                    && ack == tp.snd_nxt) {
                // goto pass
                return true;
            }

            // syn_challenge
            tcp_send_challenge_ack(net, tp);
            reason = SKB_DROP_REASON_TCP_INVALID_SYN;
            // goto discard
            tcp_drop_reason(tp, reason);
            return false;
        }
        return true;
    }

    private void tcp_drop_reason(final TcpSock tp, int reason) {
    }

    public void tcp_rcv_established(final TcpSock sk, final IpPacket ipPacket, final TcpPacket tcpPacket) throws IOException {
        // https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_input.c#L6110

        // step5
//        input.tcp_ack(this, skb, 0);

        /* step 7: process the segment text */
//        input.tcp_data_queue(this, skb);

//        input.tcp_data_snd_check(this);
        // tcp_ack_snd_check();
    }

    /**
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_input.c#L6299">tcp_init_transfer</a>
     */
    public void tcp_init_transfer(final Channel net, final TcpSock sk, final IpPacket ipPacket, final TcpPacket tcpPacket) {
        output.tcp_mtup_init(sk);
        demultiplexer.tcp_init_metrics(sk);

        sk.tcp_snd_cwnd_set(tcp_init_cwnd(sk));

        sk.snd_cwnd_stamp = tcp_jiffies32();

        demultiplexer.tcp_init_congestion_control(sk);

        // child.
        innerChannel(sk).pipeline().addLast(new ChannelInboundHandlerAdapter() {
            @Override
            public void channelRead(final ChannelHandlerContext ctx, final Object msg) throws Exception {
                try {
//                    logTrace("Read from {}", resolved);
                    final ByteBuf buf = (ByteBuf) msg;
                    final byte[] payload = ByteBufUtil.getBytes(buf);

                    // tcp data len = tcp snd.mss - tcp options.len
                    // 超过 tcp data len 不切割, 会使用TSO功能通过网卡来分段.
                    /*
                    tcp_sendmsg2(new TcpBuffer()
                            .ack(true)
                            .psh(true)
                            .payloadBuilder(
                                    UnknownPacket.newPacket(payload, 0, payload.length).getBuilder()
                            ), true);
                            */

                    final int mss = demultiplexer.output.tcp_current_mss(sk);
                    for (int offset = 0; offset < payload.length; ) {
                        final int len = payload.length - offset;
                        if (len <= mss) {
                            final UnknownPacket.Builder builder = UnknownPacket.newPacket(payload, offset, len).getBuilder();
                            demultiplexer.tcp_sendmsg2(net, sk, new TcpBuffer().ack(true)
                                    //.psh(true)
                                    .payloadBuilder(builder), true);
                            offset += len;
                        } else {
                            UnknownPacket.Builder builder = UnknownPacket.newPacket(payload, offset, mss).getBuilder();
                            demultiplexer.tcp_sendmsg2(net, sk, new TcpBuffer().ack(true)
                                    // .psh(true)
                                    .payloadBuilder(builder), false);
                            offset += mss;
                        }
                    }
                } finally {
                    ReferenceCountUtil.release(msg);
                }
            }

            @Override
            public void exceptionCaught(final ChannelHandlerContext ctx, final Throwable cause) throws Exception {
                tcpLogError(null, sk.ir_rmt_addr, sk.ir_rmt_port.valueAsInt(), sk.ir_loc_addr, sk.ir_num.valueAsInt(), "Exception caught: {}", cause.getMessage(), cause);
                demultiplexer.send_reset(net, sk.rawIpHeader, new TcpPacket.Builder()

                        .srcAddr(sk.ir_rmt_addr)
                        .dstAddr(sk.ir_loc_addr)
                        .srcPort(sk.ir_rmt_port)
                        .dstPort(sk.ir_num)
                        .ack(true)
                        .acknowledgmentNumber(sk.rcv_nxt)
                        .build(), -1);
                try {
                    if (ctx.channel().isOpen()) {
                        ctx.channel().close();
                    }
                } finally {
                    demultiplexer.tcp_done(sk);
                }
            }
        });

        // CHECK child close.

        innerChannel(sk).closeFuture().addListener(new ChannelFutureListener() {
            @Override
            public void operationComplete(ChannelFuture channelFuture) throws Exception {
                TcpHandshaker.tcpLogInfo(null, sk.ir_rmt_addr, sk.ir_rmt_port.valueAsInt(), sk.ir_loc_addr, sk.ir_num.valueAsInt(), "DISCONNECTED: {}", sk.ir_loc_addr.getHostAddress());
                if (demultiplexer.tcp_close_state(sk)) {
                    demultiplexer.output.tcp_send_fin(net, sk);
                }
            }

        });
        innerChannel(sk).config().setAutoRead(true);
    }

    private static void tcp_try_undo_spurious_syn(TcpSock sk) {

    }

    /**
     * This function implements the receiving procedure of RFC 793 for
     * all states except ESTABLISHED and TIME_WAIT.
     * It's called from both tcp_v4_rcv and tcp_v6_rcv and should be
     * address independent.
     *
     * @param ipPacket the IP packet
     * @return error code
     * @throws IOException
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_input.c#L6676">tcp_rcv_state_process</a>
     */
    protected int tcp_rcv_state_process(final Channel net, TcpSock sk,
                                        final IpPacket ipPacket, final TcpPacket tcpPacket) throws IOException {
        final TcpHeader th = tcpPacket.getHeader();

        switch (sk.state()) {
            case TCP_CLOSE:
                log.warn("[TCP_CLOSE]");
                return discard(ipPacket, tcpPacket, TcpDropReason.SKB_DROP_REASON_TCP_CLOSE);
            case TCP_LISTEN:
                if (th.getAck()) {
                    log.warn("TCP_LISTEN ACK");
                    return TcpDropReason.SKB_DROP_REASON_TCP_FLAGS;
                }
                if (th.getRst()) {
                    log.warn("TCP_LISTEN RST");
                    return discard(ipPacket, tcpPacket, TcpDropReason.SKB_DROP_REASON_TCP_RESET);
                }

                /* handshake */
                if (th.getSyn()) {
                    if (th.getFin()) {
                        log.warn("TCP_LISTEN SYN FIN");
                        return discard(ipPacket, tcpPacket, TcpDropReason.SKB_DROP_REASON_TCP_FLAGS);
                    }

                    /*-
                     * Linux此处为创建状态为TCP_NEW_SYN_RECV的请求套接字(request_sock)放入半连接队列即可结束,
                     * 此处调整为直接创建连接.
                     */
                    // FIXME sk.icsk_af_ops.conn_request(net, sk, ipPacket);
                    tcp_request_sock tcpRequestSock = demultiplexer.conn_request(net, (TcpSock) sk, ipPacket, tcpPacket);

                    if (null == tcpRequestSock) {
                        return TcpDropReason.SKB_DROP_REASON_NO_SOCKET;
                    }
                    return TcpDropReason.SKB_DROP_REASON_NOT_SPECIFIED;
                }

                return discard(ipPacket, tcpPacket, TcpDropReason.SKB_DROP_REASON_TCP_FLAGS);
            case TCP_SYN_SENT:
                /*-
                 * XXX client mode not supported.
                 */
                return TcpDropReason.SKB_DROP_REASON_NO_SOCKET;
        }

        /*-
         * 刷新最近发送/接收时间戳.
         */
        tcp_mstamp_refresh(sk);
        sk.rx_opt.saw_tstmap = 0;

        /*-
         * XXX ... fastopen_request_socket ...
         */

        if (!th.getAck() && !th.getRst() && !th.getSyn()) {
            return discard(ipPacket, tcpPacket, TcpDropReason.SKB_DROP_REASON_TCP_FLAGS);
        }

        if (!tcp_validate_incoming(net, sk, ipPacket, tcpPacket)) {
            return TcpDropReason.SKB_DROP_REASON_NOT_SPECIFIED;
        }

        /* step 5: check the ACK field */
        int reason = tcp_ack(
                net, sk, ipPacket, tcpPacket,
                TcpInput.FLAG_SLOWPATH | TcpInput.FLAG_UPDATE_TS_RECENT | TcpInput.FLAG_NO_CHALLENGE_ACK
        );
        if (reason <= 0) {
            if (TcpState.TCP_SYN_RECV.equals(sk.state())) {
                // send one RST
                return 0 == reason ? TcpDropReason.SKB_DROP_REASON_TCP_OLD_ACK : -reason;
            }

            /* accept old ack during closing */
            if (reason < 0) {
                tcp_send_challenge_ack(net, sk);
                reason = -reason;
                return discard(ipPacket, tcpPacket, reason);
            }
        }

        boolean queued = false;
        reason = TcpDropReason.SKB_DROP_REASON_NOT_SPECIFIED;
        switch (sk.state()) {
            case TCP_SYN_RECV:
                sk.delivered++; /* SYN-ACK delivery isn't tracked in tcp_ack */
                if (0 == sk.srtt_us) {
                    // FIXME
                    // tcp_synack_rtt_meas(sk, req);
                }

                if (sk.rx_opt.tstamp_ok) {
                    // sk.advmss -= TCPOLEN_TSTAMP_ALIGNED;
                }

                if (false) {
                    // FIXME fastopen..
                } else {
                    tcp_try_undo_spurious_syn(sk);
                    sk.retrans_stamp = 0;
                    tcp_init_transfer(net, sk, ipPacket, tcpPacket);
                    sk.copied_seq = sk.rcv_nxt;
                }

                sk.state(TcpState.TCP_ESTABLISHED);
                // sk.sk_state_change(sk);

                sk.snd_una = th.getAcknowledgmentNumber();
                sk.snd_wnd = th.getWindowAsInt() << sk.rx_opt.snd_wscale;
                tcp_init_wl(sk, th.getSequenceNumber());

                // ...

                /* Prevent spurious tcp_cwnd_restart() on first data packet */
                sk.lsndtime = tcp_jiffies32();
                tcp_initialize_rcv_mss(sk);

                // ...

                if (0 != (sk.sk_shutdown & SEND_SHUTDOWN)) {
                    demultiplexer.tcp_shutdown(net, sk, SEND_SHUTDOWN);
                }

                break;
            case TCP_FIN_WAIT1:
                // ... fastopen

                if (sk.snd_una != sk.write_seq) {
                    break;
                }
                sk.state(TCP_FIN_WAIT2);
                sk.sk_shutdown |= TcpConstants.SEND_SHUTDOWN;

//                if (!sock_flag(sk, SOCK_DEAD) {
//                   sk_state_change
//                   break;
//                }

                if (sk.linger2 < 0) {
                    demultiplexer.tcp_done(sk);
                    return SKB_DROP_REASON_TCP_ABORT_ON_DATA;
                }

                final int seq = th.getSequenceNumber();
                final int end_seq = determineEndSeq(tcpPacket);
                if (end_seq != seq && after(end_seq - (th.getFin() ? 1 : 0), sk.rcv_nxt)) {
                    demultiplexer.tcp_done(sk);
                    return SKB_DROP_REASON_TCP_ABORT_ON_DATA;
                }

                final int tmo = sk.tcp_fin_time();
                if (tmo > TcpConstants.TCP_TIMEWAIT_LEN) {
                    /*-
                     * FIN_WAIT2 开始的总超时时间 > TIME_WAIT 的 2MSL, 则在进入 TIME_WAIT 前保证连接存活.
                     */
                    demultiplexer.timer.tcp_reset_keepalive_timer(sk, tmo - TcpConstants.TCP_TIMEWAIT_LEN);
                } else if (th.getFin()) {
                    /* Bad case. We could lose such FIN otherwise.
                     * It is not a big problem, but it looks confusing
                     * and not so rare event. We still can lose it now,
                     * if it spins in bh_lock_sock(), but it is really
                     * marginal case.
                     */
                    demultiplexer.timer.tcp_reset_keepalive_timer(sk, tmo);
                } else {
                    demultiplexer.tcp_time_wait(sk, TCP_FIN_WAIT2, tmo);
                    return TcpDropReason.SKB_DROP_REASON_NOT_SPECIFIED;
                }
                break;
            case TCP_CLOSING:
                if (sk.snd_una == sk.write_seq) {
                    demultiplexer.tcp_time_wait(sk, TCP_TIME_WAIT, 0);
                    return TcpDropReason.SKB_DROP_REASON_NOT_SPECIFIED;
                }
                break;
            case TCP_LAST_ACK:
                if (sk.snd_una == sk.write_seq) {
                    // tcp_update_metrics
                    demultiplexer.tcp_done(sk);
                    return TcpDropReason.SKB_DROP_REASON_NOT_SPECIFIED;
                }
                break;
        }

        /* step 6: check the URG bit */
        // tcp_urg(sk, skb, th);

        /* step 7: process the segment text */
        switch (sk.state()) {
            case TCP_CLOSE_WAIT:
            case TCP_CLOSING:
            case TCP_LAST_ACK:
                if (!before(th.getSequenceNumber(), sk.rcv_nxt)) {
                    /* If a subflow has been reset, the packet should not
                     * continue to be processed, drop the packet.
                     */
                    // ... sk_is_mptcp
                    break;
                }
                // fallthrough
            case TCP_FIN_WAIT1:
            case TCP_FIN_WAIT2:
                /* RFC 793 says to queue data in these states,
                 * RFC 1122 says we MUST send a reset.
                 * BSD 4.4 also does reset.
                 */
                if (0 != (sk.sk_shutdown & TcpConstants.RCV_SHUTDOWN)) {
                    int seq = th.getSequenceNumber();
                    int end_seq = determineEndSeq(tcpPacket);
                    if (end_seq != seq && after(end_seq - (th.getFin() ? 1 : 0), sk.rcv_nxt)) {
                        tcp_reset(sk, ipPacket, tcpPacket);
                        return SKB_DROP_REASON_TCP_ABORT_ON_DATA;
                    }
                }
                // fallthrough
            case TCP_ESTABLISHED:
                tcp_data_queue(net, sk, ipPacket, tcpPacket);
                queued = true;
                break;
        }

        /* tcp_data could move socket to TIME-WAIT */
        if (!TcpState.TCP_CLOSE.equals(sk.state())) {
            tcp_data_snd_check(net, sk);
            tcp_ack_snd_check(net, sk);

            if (TcpState.TCP_CLOSE_WAIT.equals(sk.state())) {
                // FIXME
                if (null != sk.child) {
                    innerChannel(sk).close();
                } /* else if (tcp_close_state(sk)) {
                    output.tcp_send_fin(net, tp);
                } */
            }
        }

        if (!queued) {
            tcp_drop_reason(tcpPacket, reason);
        }

        return TcpDropReason.SKB_DROP_REASON_NOT_SPECIFIED;
    }


    private int discard(final IpPacket ipPacket, final TcpPacket skb, final int reason) {
        tcp_drop_reason(skb, reason);
        return 0;
    }

    private void tcp_drop_reason(final TcpPacket skb, final int reason) {

    }

    private final SecureRandom random = new SecureRandom();

    private int get_random_u32_inclusive(int a, int b) {
        return a + random.nextInt(b - a);
    }


}
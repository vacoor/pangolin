package com.github.pangolin.routing.acceptor.tun.net.handler.tcp.internal;

import lombok.extern.slf4j.Slf4j;
import org.pcap4j.packet.IpPacket;
import org.pcap4j.packet.TcpMaximumSegmentSizeOption;
import org.pcap4j.packet.TcpPacket;
import org.pcap4j.packet.TcpWindowScaleOption;

import java.io.IOException;
import java.security.SecureRandom;

import static com.github.pangolin.routing.acceptor.tun.net.handler.tcp.internal.SysctlOptions.sysctl_tcp_invalid_ratelimit;
import static com.github.pangolin.routing.acceptor.tun.net.handler.tcp.internal.TcpClock.jiffies;
import static com.github.pangolin.routing.acceptor.tun.net.handler.tcp.internal.TcpClock.tcp_jiffies32;
import static com.github.pangolin.routing.acceptor.tun.net.handler.tcp.internal.TcpConnection.*;
import static com.github.pangolin.routing.acceptor.tun.net.handler.tcp.internal.TcpConstants.*;
import static com.github.pangolin.routing.acceptor.tun.net.handler.tcp.internal.TcpConstants.HZ;
import static com.github.pangolin.routing.acceptor.tun.net.handler.tcp.internal.TcpDropReason.*;
import static com.github.pangolin.routing.acceptor.tun.net.handler.tcp.internal.TcpState.TCP_SYN_RECV;
import static com.github.pangolin.routing.acceptor.tun.net.handler.tcp.internal.TcpTimer.ICSK_ACK_NOW;
import static com.github.pangolin.routing.acceptor.tun.net.handler.tcp.internal.TcpTimer.ICSK_TIME_PROBE0;
import static com.github.pangolin.routing.acceptor.tun.net.handler.tcp.internal.TcpUtils.*;

@Slf4j
class TcpInput<T extends IpPacket> {
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

    private final TcpOutput<T> output;

    public TcpInput(final TcpOutput<T> output) {
        this.output = output;
    }

    /**
     * If the current remaining number of quickly ACKs is
     * less than the recalculated number of quickly ACKs, increment it.
     *
     * @param max_quickacks the maximum times of quickly ACKs allowed
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_input.c#L300">tcp_incr_quickack</a>
     */
    protected void tcp_incr_quickack(final TcpConnection<T> tp, int max_quickacks) {
        int quickacks = tp.rcv_wnd / (2 * tp.icsk_ack_rcv_mss);
        if (0 == quickacks) {
            quickacks = 2;
        }
        quickacks = Math.min(quickacks, max_quickacks);
        if (quickacks > tp.icsk_ack_quick) {
            tp.logTrace("[QUICK-ACK] increment QUICK-ARK count: {} -> {}", tp.icsk_ack_quick, quickacks);
            tp.icsk_ack_quick = quickacks;
        }
    }

    /**
     * Enter quickly ACK mode and calculate the number of quickly ACKs,
     * exit ping-pong mode, and reset the delay ACK timeout.
     *
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_input.c#L318">tcp_enter_quickack_mode</a>
     */
    protected void tcp_enter_quickack_mode(TcpConnection<T> tp, int max_quickacks) {
        tp.logTrace("[QUICK-ACK] enter QUICK-ARK count: {} -> {}", tp.icsk_ack_quick, max_quickacks);
        tcp_incr_quickack(tp, max_quickacks);
        tp.inet_csk_exit_pingpong_mode();
        tp.icsk_ack_ato = TCP_ATO_MIN;
    }

    /*-
     * Send ACKs quickly, if "quick" count is not exhausted
     * and the session is not interactive.
     *
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_input.c#L318">tcp_in_quickack_mode</a>
     */
    protected boolean tcp_in_quickack_mode(TcpConnection<T> tp) {
        return tp.icsk_ack_quick > 0 && !tp.inet_csk_in_pingpong_model();
    }

    /**
     * Check if sending an ack is needed.
     * <p>
     * https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_input.c#L5760.
     */
    private void __tcp_ack_snd_check(final TcpConnection<T> tp) {
        if (
            // (tp.rcv_nxt - tp.rcv_wup > tp.icsk_ack_rcv_mss
            /* ... and right edge of window advances far enough.
             * (tcp_recvmsg() will send ACK otherwise).
             * If application uses SO_RCVLOWAT, we want send ack now if
             * we have not received enough bytes to satisfy the condition.
             */
            // && (tp.rcv_nxt - tp.copied_seq < tp.sk_rcvlowat || tp.output.__tcp_select_window(tp) >= tp.rcv_wnd)
            // ) ||
                tcp_in_quickack_mode(tp) || 0 != (tp.icsk_ack_pending & ICSK_ACK_NOW)) {
            output.tcp_send_ack(tp);
            return;
        }

        output.tcp_send_delayed_ack(tp);

        // ...
    }

    /**
     * Check if sending an ack is needed.
     *
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_input.c#L5827">tcp_ack_snd_check</a>
     */
    void tcp_ack_snd_check(TcpConnection<T> tp) {
        if (!tp.inet_csk_ack_scheduled()) {
            /* We sent a data segment already. */
            return;
        }
        __tcp_ack_snd_check(tp);
    }

    private void tcp_rcv_nxt_update(TcpConnection<T> tp, final int seq) {
        final int delta = seq - tp.rcv_nxt;
        tp.bytes_received += delta;
        // tcp_rcv_sne_update(seq)
        tp.rcv_nxt = seq;
    }

    private void tcp_queue_rcv(final TcpConnection<T> tp, final TcpPacket skb) {
        // https://www.cnblogs.com/wanpengcoder/p/11752122.html
        tcp_rcv_nxt_update(tp, determineEndSeq(skb));
    }


    private void out_of_window(TcpConnection<T> tp, final TcpPacket skb, final int reason) {
        tcp_enter_quickack_mode(tp, TCP_MAX_QUICKACKS);
        tp.inet_csk_schedule_ack();
        drop(skb, reason);
    }

    private void drop(final TcpPacket skb, final int reason) {
        // tcp_drop_reason(sk, skb, reason);
    }


    private void queue_and_out(TcpConnection<T> tp, final TcpPacket skb) throws IOException {
        final TcpPacket.TcpHeader hdr = skb.getHeader();
        // queue_and_out;

        tp.inet_csk_schedule_ack();

        tp.sk_data_ready();
        if (skb.length() - hdr.length() > 0) {
            tp.consume(skb);
        }

        tcp_queue_rcv(tp, skb);

        if (skb.length() - hdr.length() > 0) {
            tcp_event_data_recv(tp, skb);
        }

        if (hdr.getFin()) {
            tcp_fin(tp);
        }
    }

    /**
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_input.c#L820">tcp_event_data_recv</a>
     */
    private void tcp_event_data_recv(TcpConnection<T> tp, final TcpPacket skb) throws IOException {
        tp.inet_csk_schedule_ack();

        tp.tcp_measure_rcv_mss(skb);
        tp.tcp_rcv_rtt_measure();

        long now = tcp_jiffies32();
        if (0 == tp.icsk_ack_ato) {
            /*
             * The _first_ data packet received, initialize
             * delayed ACK engine.
             */
            tcp_incr_quickack(tp, TCP_MAX_QUICKACKS);
            tp.icsk_ack_ato = TCP_ATO_MIN;
        } else {
            long m = now - tp.icsk_ack_lrcvtime;
            /*-
             * 1. 如果两次收到数据的间隔 <= TCP_ATO_MIN / 2, ato = ato / 2 + TCP_ATO_MIN / 2
             * 2. 如果收到数据间隔 > TCP_ATO_MIN / 2 && < ato, ato = ato / 2 + 间隔, 最大不超过rto
             */
            if (m <= TCP_ATO_MIN / 2) {
                /* The fastest case is the first. */
                tp.icsk_ack_ato = (tp.icsk_ack_ato >> 1) + TCP_ATO_MIN / 2;
            } else if (m < tp.icsk_ack_ato) {
                tp.icsk_ack_ato = (tp.icsk_ack_ato >> 1) + m;
                if (tp.icsk_ack_ato > tp.icsk_rto) {
                    tp.icsk_ack_ato = tp.icsk_rto;
                }
            } else if (m > tp.icsk_ack_ato) {
                /*-
                 * Too long gap. Apparently sender failed to
                 * restart window, so that we send ACKs quickly.
                 */
                tcp_incr_quickack(tp, TCP_MAX_QUICKACKS);
            }
        }
        tp.icsk_ack_lrcvtime = now;

        // ...
    }

    void tcp_rcv_spurious_retrans(final TcpPacket skb) {

    }

    void tcp_data_queue_ofo(final TcpPacket skb) {

    }

    /**
     * @param skb
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_input.c#L5229">tcp_input.c</a>
     */
    void tcp_data_queue(final TcpConnection<T> tp, final TcpPacket skb) throws IOException {
        final TcpPacket.TcpHeader hdr = skb.getHeader();
        final int seq = hdr.getSequenceNumber();
        final int endSeq = determineEndSeq(skb);

        if (seq == endSeq) {
            return;
        }

        if (seq == tp.rcv_nxt) {
            if (output.tcp_receive_window(tp) == 0) {
                final int len = skb.length() - hdr.length();
                if (!(0 >= len && hdr.getFin())) {
                    out_of_window(tp, skb, TcpDropReason.SKB_DROP_REASON_TCP_ZEROWINDOW);
                    return;
                }
            }

            /* Ok. In sequence. In window. */
            queue_and_out(tp, skb);
        } else if (!after(endSeq, tp.rcv_nxt)) {
            tcp_rcv_spurious_retrans(skb);
            /* A retransmit, 2nd most common case.  Force an immediate ack. */
            out_of_window(tp, skb, TcpDropReason.SKB_DROP_REASON_TCP_OLD_DATA);
        } else if (!before(seq, tp.rcv_nxt + output.tcp_receive_window(tp))) {
            /* Out of window. F.e. zero window probe. */
            out_of_window(tp, skb, TcpDropReason.SKB_DROP_REASON_TCP_OVERWINDOW);
        } else if (before(seq, tp.rcv_nxt)) {
            /* Partial packet, seq < rcv_next < end_seq */
            if (output.tcp_receive_window(tp) == 0) {
                out_of_window(tp, skb, TcpDropReason.SKB_DROP_REASON_TCP_ZEROWINDOW);
            } else {
                // goto queue_and_out
                queue_and_out(tp, skb);
            }
        } else {
            tcp_data_queue_ofo(skb);
        }
    }

    // https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_input.c#L4521
    void tcp_done_with_error(final TcpConnection<T> tp, int err) {
        tp.logError("TCP DONE WITH ERROR: {}", err);
        // https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_input.c#L4515
        tp.tcp_done();
    }

    /**
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_input.c#L4530">tcp_reset</a>
     */
    void tcp_reset(TcpConnection<T> tp, final TcpPacket skb) {
        int err;
        switch (tp.state.get()) {
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
    private void tcp_fin(TcpConnection<T> tp) {
        tp.inet_csk_schedule_ack();

        tp.sk_shutdown |= RCV_SHUTDOWN;

        final TcpState state = tp.state.get();
        switch (state) {
            case TCP_SYN_RECV:
            case TCP_ESTABLISHED:
                /* Move to CLOSE_WAIT */
                tp.state.set(TcpState.TCP_CLOSE_WAIT);
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
                output.tcp_send_ack(tp);
                tp.state.set(TcpState.TCP_CLOSING);
                break;
            case TCP_FIN_WAIT2:
                /* Received a FIN -- send ACK and enter TIME_WAIT. */
                output.tcp_send_ack(tp);
                tp.tcp_time_wait(TcpState.TCP_TIME_WAIT, 0);
                break;
            default:
                /* Only TCP_LISTEN and TCP_CLOSE are left, in these
                 * cases we should never reach this piece of code.
                 */
                log.error("tcp_fin(): Impossible, sk->sk_state={}", state);
                break;
        }

        // TODO
    }

    private void tcp_in_ack_event(int ack_env_flags) {

    }

    private int tcp_clean_rtx_queue(TcpConnection<T> tp, int prior_snd_una) {
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
            seq_rtt_us = tp.tcp_stamp_us_delta(tp.tcp_mstamp, first_ackt);
            ca_rtt_us = tp.tcp_stamp_us_delta(tp.tcp_mstamp, last_ackt);

            tp.logTrace("[RTT] Seq {} round-trip-time: {}us", first_ackseq, seq_rtt_us);
        }

        /*-
         * 更新 RTT, RTO.
         */

        // FIXME
        tp.tcp_ack_update_rtt(flag, seq_rtt_us, 0, ca_rtt_us);

        return 0;
    }

    /**
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_input.c#L3546">tcp_ack_probe</a>
     */
    private void tcp_ack_probe(TcpConnection<T> tp) {
        final TcpBuffer head = tp.tcp_send_head();

        /* Was it a usable window open? */
        if (null == head) {
            return;
        }
        if (!after(determineEndSeq(head), tp.tcp_wnd_end())) {
            tp.icsk_backoff = 0;
            tp.icsk_probes_tstamp = 0;
            tp.inet_csk_clear_xmit_timer(ICSK_TIME_PROBE0);
            /* Socket must be waked up by subsequent tcp_data_snd_check().
             * This function is not for random using!
             */
        } else {
            long when = tp.tcp_probe0_when(TCP_RTO_MAX);
            when = tp.tcp_clamp_probe0_to_user_timeout(when);
            tp.tcp_reset_xmit_timer(ICSK_TIME_PROBE0, when, true);
        }
    }

    /**
     * @param ack
     * @param ack_seq
     * @param nwin
     * @return
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_input.c#L3620">tcp_may_update_window</a>
     */
    private boolean tcp_may_update_window(TcpConnection<T> tp, final int ack, final int ack_seq, final int nwin) {
        return ack > tp.snd_una
                || ack_seq > tp.snd_wl1
                || (ack_seq == tp.snd_wl1 && (nwin > tp.snd_wnd || nwin == 0));
    }

    /**
     * @param ack
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_input.c#L3629">tcp_snd_sne_update</a>
     */
    private void tcp_snd_sne_update(TcpConnection<T> tp, int ack) {

    }

    /**
     * Update our send window.
     * <p>
     * Window update algorithm, described in RFC793/RFC1122 (used in linux-2.2
     * and in FreeBSD. NetBSD's one is even worse.) is wrong.
     *
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_input.c#L3696">tcp_ack_update_window</a>
     */
    private int tcp_ack_update_window(TcpConnection<T> tp, final TcpPacket.TcpHeader tcpHdr, final int ack, final int ack_seq) {
        int flag = 0;
        int nwin = tcpHdr.getWindowAsInt();

        if (!tcpHdr.getSyn()) {
            nwin <<= tp.snd_wscale;
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


    void tcp_parse_options(TcpConnection<T> tp, final TcpPacket skb, final boolean estab) {
        // https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_input.c#L4183
        final TcpPacket.TcpHeader hdr = skb.getHeader();
        for (final TcpPacket.TcpOption option : hdr.getOptions()) {
            if (option instanceof TcpMaximumSegmentSizeOption && hdr.getSyn() && !estab) {
                int inMss = ((TcpMaximumSegmentSizeOption) option).getMaxSegSizeAsInt();
                if (inMss > 0) {
                    int user_mss = tp.tmp_opt_rx_user_mss.get();
                    inMss = user_mss > 0 && user_mss < inMss ? user_mss : inMss;
                    tp.tmp_opt_rx_mss_clamp.set(inMss);
                }
            } else if (option instanceof TcpWindowScaleOption && hdr.getSyn() && !estab && SysctlOptions.sysctl_tcp_window_scaling) {
                final byte wscale = ((TcpWindowScaleOption) option).getShiftCount();
                tp.tmp_opt_wscale_ok.set(true);
                tp.tmp_opt_snd_wscale.set(wscale > TCP_MAX_WSCALE ? TCP_MAX_WSCALE : wscale);
            }
        }
    }


    // https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_input.c#L5739
    void tcp_check_space() {
        // FIXME
    }

    /**
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_input.c#L5664">tcp_data_snd_check</a>
     */
    protected void tcp_data_snd_check(final TcpConnection<T> tp) {
        tp.tcp_push_pending_frames();
        tcp_check_space();
    }


    private boolean tcp_reset_check(TcpConnection<T> tp, final TcpPacket skb) {
        // https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_input.c#L5939
        final int seq = skb.getHeader().getSequenceNumber();
        return seq == tp.rcv_nxt - 1 && 0 != ((1 << tp.state.get().ordinal()) | (TCPF_CLOSE_WAIT | TCPF_LAST_ACK | TCPF_CLOSING));
    }

    /**
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_input.c#L5870">tcp_validate_incoming</a>
     */
    boolean tcp_validate_incoming(TcpConnection<T> tp, final TcpPacket skb) {
        final TcpPacket.TcpHeader th = skb.getHeader();
        final int seq = th.getSequenceNumber();
        final int end_seq = determineEndSeq(skb);
        final int ack = th.getAcknowledgmentNumber();

        int reason = tcp_disordered_ack_check(tp, skb);
        if (0 == reason) {
            // goto step1;
        }

        /* Reset is accepted even if it did not pass PAWS. */
        if (0 == reason || th.getRst()) {
            // goto step1
        } else if (th.getSyn()) {
            // goto syn_challenge
            tcp_send_challenge_ack(tp);
            reason = SKB_DROP_REASON_TCP_INVALID_SYN;
//            goto discard;
            tcp_drop_reason(tp, reason);
            return false;
        } else if (reason == SKB_DROP_REASON_TCP_RFC7323_PAWS_ACK) {
            // goto discard;
            tcp_drop_reason(tp, reason);
            return false;
        } else if (!tcp_oow_rate_limited(tp, skb, 0, tp.last_oow_ack_time)) {
            tcp_send_dupack(tp, skb);
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
                    tcp_send_challenge_ack(tp);
                    reason = SKB_DROP_REASON_TCP_RESET;
                    // goto discard;
                    tcp_drop_reason(tp, reason);
                    return false;
                }

                if (reason == SKB_DROP_REASON_TCP_INVALID_SEQUENCE
                        || reason == SKB_DROP_REASON_TCP_INVALID_END_SEQUENCE) {
                    // stats
                }

                if (!tcp_oow_rate_limited(tp, skb, 1, tp.last_oow_ack_time)) {
                    tcp_send_dupack(tp, skb);
                }
            } else if (tcp_reset_check(tp, skb)) {
                // goto reset
                tcp_reset(tp, skb);
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
            if (th.getSequenceNumber() == tp.rcv_nxt || tcp_reset_check(tp, skb)) {
                // goto reset
                tcp_reset(tp, skb);
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

            tcp_send_challenge_ack(tp);
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
            TcpState tcpState = tp.state.get();
            if (TCP_SYN_RECV.equals(tcpState)
                    && th.getAck()
                    && seq + 1 == end_seq
                    && seq + 1 == tp.rcv_nxt
                    && ack == tp.snd_nxt) {
                // goto pass
                return true;
            }

            // syn_challenge
            tcp_send_challenge_ack(tp);
            reason = SKB_DROP_REASON_TCP_INVALID_SYN;
            // goto discard
            tcp_drop_reason(tp, reason);
            return false;
        }
        return true;
    }

    private void tcp_drop_reason(final TcpConnection<T> tp, int reason) {
    }


    private int tcp_disordered_ack_check(final TcpConnection<T> tp, final TcpPacket skb) {
        int reason = TCP_RFC7323_PAWS;
        TcpPacket.TcpHeader th = skb.getHeader();
        int seq = th.getSequenceNumber();
        int ack = th.getAcknowledgmentNumber();

        /* 1. Is this not a pure ACK ? */
        if (!th.getAck() || seq != determineEndSeq(skb)) {
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

    private void tcp_send_dupack(final TcpConnection<T> tp, final TcpPacket skb) {
        TcpPacket.TcpHeader th = skb.getHeader();
        int seq = th.getSequenceNumber();
        int end_seq = determineEndSeq(skb);
        if (end_seq != seq && before(seq, tp.rcv_nxt)) {
            tcp_enter_quickack_mode(tp, TCP_MAX_QUICKACKS);

            // if tcp_is_sack ...
        }

        output.tcp_send_ack(tp);
    }

    /**
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_input.c#L4394">tcp_sequence</a>
     */
    private int tcp_sequence(final TcpConnection<T> tp, final int seq, final int end_seq) {
        if (before(end_seq, tp.rcv_wup)) {
            return SKB_DROP_REASON_TCP_OLD_SEQUENCE;
        }
        if (after(end_seq, tp.rcv_nxt + tp.output.tcp_receive_window(tp))) {
            if (after(seq, tp.rcv_nxt + tp.output.tcp_receive_window(tp))) {
                return SKB_DROP_REASON_TCP_INVALID_SEQUENCE;
            }

            /* Only accept this packet if receive queue is empty. */
//            if (skb_queue_len(&sk->sk_receive_queue)){
//                return SKB_DROP_REASON_TCP_INVALID_END_SEQUENCE;
//            }
        }
        return SKB_NOT_DROPPED_YET;
    }

    /**
     * If we update tp->snd_una, also update tp->bytes_acked.
     *
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_input.c#L3647">tcp_snd_una_update</a>
     */
    private void tcp_snd_una_update(TcpConnection<T> tp, final int ack) {
        final int delta = ack - tp.snd_una;
        tp.bytes_acked += delta;
        tcp_snd_sne_update(tp, ack);
        tp.snd_una = ack;
    }


    /**
     * This routine deals with incoming acks, but not outgoing ones.
     *
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_input.c#L3904">tcp_ack</a>
     */
    int tcp_ack(final TcpConnection<T> tp, final TcpPacket skb, int flag) {
        final TcpPacket.TcpHeader tcpHdr = skb.getHeader();
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
                    tcp_send_challenge_ack(tp);
                }
                log.warn("TOO OLD ACK on {}: ACK({}) < SND.UNA({}), {}", tp.state.get(), ack, prior_snd_una, tcpHdr);
                return -TcpDropReason.SKB_DROP_REASON_TCP_TOO_OLD_ACK;
            }
            // goto old_ack.
            log.warn("OLD ACK on {}: ACK({}) < SND.UNA({}), {}", tp.state.get(), ack, prior_snd_una, tcpHdr);
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
            if (ack_seq != determineEndSeq(skb)) {
                flag |= FLAG_DATA;
            }

            flag |= tcp_ack_update_window(tp, tcpHdr, ack, ack_seq);

//            if (skb.sacked) {
//                flag |= tcp_sacktag_write_queue(sk, skb, prior_snd_una, &sack_state);
//            }

//            if (sack_state.sack_delivered) {
//                tcp_count_delivered(tp, sack_state.sack_delivered, flag & FLAG_ECE);
//            }

        }

//        tcp_in_ack_event(CA_ACK_WIN_UPDATE);
        /*-
         * We passed data and got it acked, remove any soft error log. Something worked...
         */
        tp.sk_err_soft = 0;
        tp.icsk_probes_out = 0;
        tp.rcv_tstamp = tcp_jiffies32();

        if (prior_packets_out == 0) {
            // goto no_queue.
            tcp_in_ack_event(flag);

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

        tcp_in_ack_event(flag);

//        if (tp->tlp_high_seq)
//            tcp_process_tlp_ack(sk, ack, flag);
        // TODO ...

        // tcp_cong_control();

        return 1;
    }

    /**
     *
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_input.c#L3606"></a>
     */
    private boolean __tcp_oow_rate_limited(TcpConnection<T> net, int mib_idx, long last_oow_ack_time) {
//        final long last_oow_ack_time = net.last_oow_ack_time;
        if (0 != last_oow_ack_time) {
            final long elapsed = tcp_jiffies32() - last_oow_ack_time;
            if (0 <= elapsed && elapsed < net.ipv4_sysctl_tcp_invalid_ratelimit) {
                return true;/* rate-limited: don't send yet! */
            }
        }

        net.last_oow_ack_time = tcp_jiffies32();

        return false;	/* not rate-limited: go ahead, send dupack now! */
    }

    /* Return true if we're currently rate-limiting out-of-window ACKs and
     * thus shouldn't send a dupack right now. We rate-limit dupacks in
     * response to out-of-window SYNs or ACKs to mitigate ACK loops or DoS
     * attacks that send repeated SYNs or ACKs for the same connection. To
     * do this, we do not send a duplicate SYNACK or ACK if the remote
     * endpoint is sending out-of-window SYNs or pure ACKs at a high rate.
     */
    private boolean tcp_oow_rate_limited(TcpConnection<T> net, TcpPacket skb, int mib_idx, long last_oow_ack_time) {
        final TcpPacket.TcpHeader th = skb.getHeader();
        /* Data packets without SYNs are not likely part of an ACK loop. */
        if ((th.getSequenceNumber() != determineEndSeq(skb)) && !th.getSyn()) {
            return false;
        }
        return __tcp_oow_rate_limited(net, mib_idx, last_oow_ack_time);
    }

    /**
     * RFC 5961 7 [ACK Throttling]
     *
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_input.c#L3649">tcp_send_challenge_ack</a>
     * @param tp
     */
    private void tcp_send_challenge_ack(TcpConnection<T> tp) {
        /* First check our per-socket dupack rate limit. */
        if (__tcp_oow_rate_limited(tp, 0, tp.last_oow_ack_time)) {
            return;
        }

        int ack_limit = tp.ipv4_sysctl_tcp_challenge_ack_limit;
        if (ack_limit == Integer.MAX_VALUE) {
            output.tcp_send_ack(tp);
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
            output.tcp_send_ack(tp);
        }
    }

    private final SecureRandom random = new SecureRandom();

    private int get_random_u32_inclusive(int a, int b) {
        return a + random.nextInt(b - a);
    }

    public void tcp_sack_compress_send_ack(TcpConnection<T> tp) {
        // FIXME
    }
}
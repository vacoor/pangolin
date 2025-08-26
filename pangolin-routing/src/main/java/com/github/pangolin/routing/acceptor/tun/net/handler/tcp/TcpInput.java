package com.github.pangolin.routing.acceptor.tun.net.handler.tcp;

import lombok.extern.slf4j.Slf4j;
import org.pcap4j.packet.IpPacket;
import org.pcap4j.packet.TcpMaximumSegmentSizeOption;
import org.pcap4j.packet.TcpPacket;
import org.pcap4j.packet.TcpWindowScaleOption;

import java.io.IOException;

import static com.github.pangolin.routing.acceptor.tun.net.handler.tcp.TcpConnection.*;
import static com.github.pangolin.routing.acceptor.tun.net.handler.tcp.TcpConstants.*;
import static com.github.pangolin.routing.acceptor.tun.net.handler.tcp.TcpTimer.ICSK_ACK_NOW;
import static com.github.pangolin.routing.acceptor.tun.net.handler.tcp.TcpTimer.ICSK_TIME_PROBE0;
import static com.github.pangolin.routing.acceptor.tun.net.handler.tcp.TcpUtils.*;

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
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_input.c#L300">tcp_incr_quickack</a>
     */
    void tcp_incr_quickack(final TcpConnection<T> tp, int max_quickacks) {
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
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_input.c#L318">tcp_enter_quickack_mode</a>
     */
    void tcp_enter_quickack_mode(TcpConnection<T> tp, int max_quickacks) {
        tp.logTrace("[QUICK-ACK] enter QUICK-ARK count: {} -> {}", tp.icsk_ack_quick, max_quickacks);
        tcp_incr_quickack(tp, max_quickacks);
        tp.inet_csk_exit_pingpong_mode();
        tp.icsk_ack_ato = TCP_ATO_MIN;
    }

    /*-
     * Send ACKs quickly, if "quick" count is not exhausted
     * and the session is not interactive.
     */

    /**
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_input.c#L318">tcp_in_quickack_mode</a>
     */
    private boolean tcp_in_quickack_mode(TcpConnection<T> tp) {
        //const struct dst_entry *dst = __sk_dst_get(sk);

        /*
        return (dst && dst_metric(dst, RTAX_QUICKACK)) ||
                (icsk->icsk_ack.quick && !inet_csk_in_pingpong_mode(sk));
                */
        return tp.icsk_ack_quick > 0 && !tp.inet_csk_in_pingpong_model();
    }

    /**
     * Check if sending an ack is needed.
     *
     * https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_input.c#L5760.
     */
    private void __tcp_ack_snd_check(final TcpConnection<T> tp) {
        if (tcp_in_quickack_mode(tp) || 0 != (tp.icsk_ack_pending & ICSK_ACK_NOW)) {
            output.tcp_send_ack(tp);
            return;
        }

        output.tcp_send_delayed_ack(tp);
    }

    /**
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
                    out_of_window(tp, skb, SKB_DROP_REASON_TCP_ZEROWINDOW);
                    return;
                }
            }

            /* Ok. In sequence. In window. */
            queue_and_out(tp, skb);
        } else if (!after(endSeq, tp.rcv_nxt)) {
            tcp_rcv_spurious_retrans(skb);
            /* A retransmit, 2nd most common case.  Force an immediate ack. */
            out_of_window(tp, skb, SKB_DROP_REASON_TCP_OLD_DATA);
        } else if (!before(seq, tp.rcv_nxt + output.tcp_receive_window(tp))) {
            /* Out of window. F.e. zero window probe. */
            out_of_window(tp, skb, SKB_DROP_REASON_TCP_OVERWINDOW);
        } else if (before(seq, tp.rcv_nxt)) {
            /* Partial packet, seq < rcv_next < end_seq */
            if (output.tcp_receive_window(tp) == 0) {
                out_of_window(tp, skb, SKB_DROP_REASON_TCP_ZEROWINDOW);
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

        final State state = tp.state.get();
        switch (state) {
            case TCP_SYN_RECV:
            case TCP_ESTABLISHED:
                /* Move to CLOSE_WAIT */
                tp.state.set(State.TCP_CLOSE_WAIT);
                tp.inet_csk_enter_pingpong_mode();

                // FIXME
                if (null != tp.child && tp.child.isOpen()) {
                    tp.child.close();
                } else {
                    tp.shutdown(SEND_SHUTDOWN);
                }

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
                tp.state.set(State.TCP_CLOSING);
                break;
            case TCP_FIN_WAIT2:
                /* Received a FIN -- send ACK and enter TIME_WAIT. */
                output.tcp_send_ack(tp);
                // tcp_time_wait(sk, State.TCP_TIME_WAIT, 0);
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

        int first_ackt = 0;
        int first_ackseq = 0;
        int last_ackt = 0;
        int flag = 0;
        boolean fully_acked = true;
        int seq_rtt_us = 0;
        int ca_rtt_us = 0;

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

            tp.logTrace("Seq {} round-trip-time: {}us", first_ackseq, seq_rtt_us);
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
            tp.tcp_reset_xmit_timer(ICSK_TIME_PROBE0, when, TCP_RTO_MAX);
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
            } else if (option instanceof TcpWindowScaleOption && hdr.getSyn() && !estab && tp.sysctl_tcp_window_scaling) {
                final byte wscale = ((TcpWindowScaleOption) option).getShiftCount();
                tp.tmp_opt_wscale_ok.set(true);
                tp.tmp_opt_snd_wscale.set(wscale > TCP_MAX_WSCALE ? TCP_MAX_WSCALE : wscale);
            }
        }
    }


    // https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_input.c#L5739
    void tcp_check_space() {
    }

    /**
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_input.c#L5745">tcp_data_snd_check</a>
     */
    void tcp_data_snd_check(TcpConnection<T> tp) {
        tp.tcp_push_pending_frames();
        tcp_check_space();
    }


    private boolean tcp_reset_check(TcpConnection<T> tp, final TcpPacket skb) {
        // https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_input.c#L5939
        int seq = skb.getHeader().getSequenceNumber();
        return seq == tp.rcv_nxt - 1 && 0 != ((1 << tp.state.get().ordinal()) | (TCPF_CLOSE_WAIT | TCPF_LAST_ACK | TCPF_CLOSING));
    }

    // https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_input.c#L5957
    boolean tcp_validate_incoming(TcpConnection<T> tp, final TcpPacket skb) {
        final TcpPacket.TcpHeader hdr = skb.getHeader();
        if (hdr.getRst()) {
            if (hdr.getSequenceNumber() == tp.rcv_nxt || tcp_reset_check(tp, skb)) {
                // reset
                tcp_reset(tp, skb);
                return false;
            }
        }
        return true;
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
            int max_window = Math.min(tp.max_window, tp.bytes_acked);
            /* RFC 5961 5.2 [Blind Data Injection Attack].[Mitigation] */
            if (before(ack, prior_snd_una - max_window)) {
                // TODO
            }

            log.warn("{}: {} < {}", tp.state.get(), ack, prior_snd_una);
            return 0;
        }

        /*-
         * If the ack includes data we haven't sent yet,
         * discard this segment (RFC793 Section 3.9).
         */
        if (after(ack, tp.snd_nxt)) {
            return -SKB_DROP_REASON_TCP_ACK_UNSENT_DATA;
        }

        if (after(ack, prior_snd_una)) {
            flag |= FLAG_SND_UNA_ADVANCED;
            tp.icsk_retransmits = 0;
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

            tcp_in_ack_event(CA_ACK_WIN_UPDATE);
        } else {
            int ack_ev_flags = CA_ACK_SLOWPATH;
            if (ack_seq != determineEndSeq(skb)) {
                flag |= FLAG_DATA;
            }

            flag |= tcp_ack_update_window(tp, tcpHdr, ack, ack_seq);


            if (0 != (flag & FLAG_WIN_UPDATE)) {
                ack_ev_flags |= CA_ACK_WIN_UPDATE;
            }
            tcp_in_ack_event(ack_ev_flags);
        }

        tp.sk_err_soft = 0;
        tp.icsk_probes_out = 0;
        tp.rcv_tstamp = tcp_jiffies32();

        if (prior_packets_out == 0) {
            // no_queue.
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
        return 1;
    }
}
package com.github.pangolin.routing.server.tun.net.handler.tcp;

import lombok.extern.slf4j.Slf4j;
import org.pcap4j.packet.IpPacket;
import org.pcap4j.packet.TcpPacket;

import java.io.IOException;

import static com.github.pangolin.routing.server.tun.net.handler.tcp.TcpConnection.*;
import static com.github.pangolin.routing.server.tun.net.handler.tcp.TcpUtils.*;

@Slf4j
class TcpInput<T extends IpPacket> {
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
}
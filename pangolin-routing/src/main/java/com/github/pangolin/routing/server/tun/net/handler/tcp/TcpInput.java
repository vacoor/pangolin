package com.github.pangolin.routing.server.tun.net.handler.tcp;

import static com.github.pangolin.routing.server.tun.net.handler.tcp.TcpConnection.ICSK_ACK_NOW;
import static com.github.pangolin.routing.server.tun.net.handler.tcp.TcpConnection.TCP_ATO_MIN;

import org.pcap4j.packet.IpPacket;

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
    boolean tcp_in_quickack_mode(TcpConnection<T> tp) {
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
}
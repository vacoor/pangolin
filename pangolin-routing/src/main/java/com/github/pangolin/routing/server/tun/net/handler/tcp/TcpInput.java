package com.github.pangolin.routing.server.tun.net.handler.tcp;

import static com.github.pangolin.routing.server.tun.net.handler.tcp.TcpConnection.ICSK_ACK_NOW;

import org.pcap4j.packet.IpPacket;

class TcpInput<T extends IpPacket> {
    private final TcpOutput<T> output;

    public TcpInput(final TcpOutput<T> output) {
        this.output = output;
    }

    /**
     * Check if sending an ack is needed.
     *
     * https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_input.c#L5760.
     */
    private void __tcp_ack_snd_check(final TcpConnection<T> tp) {
        if (tp.tcp_in_quickack_mode() || 0 != (tp.icsk_ack_pending & ICSK_ACK_NOW)) {
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
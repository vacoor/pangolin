package com.github.pangolin.routing.acceptor.tun.net.handler.tcp.sock;

import com.github.pangolin.routing.acceptor.tun.net.handler.tcp.core.tcp_request_sock_ops;

/**
 * @see <a href="https://github.com/torvalds/linux/blob/master/include/linux/tcp.h#L150">struct tcp_request_sock</a>
 */
public class tcp_request_sock extends inet_request_sock {
    public tcp_request_sock_ops af_specific;
    public boolean req_usec_ts;
    public int rcv_isn;
    public int snt_isn;
    public long ts_off;
    public int snt_tsval_first;
    public int snt_tsval_last;
    public int last_oow_ack_time;
    public int rcv_nxt;
    public int syn_tos;
    /** Initial send window from the SYN packet (client's advertised window). */
    public int snd_wnd;

    /** Number of SYN-ACK retransmissions sent (0 = only the initial send). */
    public int num_retrans;

    /**
     * Peer's TSval from SYN — used to seed ts_recent in the child socket.
     *
     * @see <a href="https://github.com/torvalds/linux/blob/master/include/linux/tcp.h#L172">tcp_request_sock.ts_recent</a>
     */
    public long ts_recent;

}

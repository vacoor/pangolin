package com.github.pangolin.routing.acceptor.tun.net.handler.tcp.internal;

import com.github.pangolin.routing.acceptor.tun.net.handler.tcp.core.tcp_request_sock_ops;

// https://github.com/torvalds/linux/blob/master/include/linux/tcp.h#L149
public class tcp_request_sock extends inet_request_sock {
    public tcp_request_sock_ops af_specific;

    public int mss;
    public boolean req_usec_ts;
    public int rcv_isn;
    public int snt_isn;
    public long ts_off;
    int snt_tsval_first;
    int snt_tsval_last;
    int last_oow_ack_time;
    public int rcv_nxt;
    int syn_tos;

    public TcpSock parentSock;

}

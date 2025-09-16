package com.github.pangolin.routing.acceptor.tun.net.handler.tcp.internal;

// https://github.com/torvalds/linux/blob/master/include/linux/tcp.h#L149
public class tcp_request_sock extends inet_request_sock {
    int mss;
    boolean req_usec_ts;
    int rcv_isn;
    int snt_isn;
    long ts_off;
    int snt_tsval_first;
    int snt_tsval_last;
    int last_oow_ack_time;
    int rcv_nxt;
    int syn_tos;
}

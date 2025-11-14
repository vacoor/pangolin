package com.github.pangolin.routing.acceptor.tun.net.handler.tcp.internal;

import com.github.pangolin.routing.acceptor.tun.net.handler.tcp.v2.SockCommon;
import com.github.pangolin.routing.acceptor.tun.net.handler.tcp.v2.TcpSock;

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

    public TcpSock parentSock;

}

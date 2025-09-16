package com.github.pangolin.routing.acceptor.tun.net.handler.tcp.internal;

// https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_ipv4.c#L1705
public interface tcp_request_sock_ops {

    void send_ack();

    void send_reset();

}

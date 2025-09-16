package com.github.pangolin.routing.acceptor.tun.net.handler.tcp.internal;

// https://github.com/torvalds/linux/blob/master/include/net/inet_sock.h#L69
public class inet_request_sock extends request_sock {
    public int snd_wscale;
    public int rcv_wscale;
    public boolean wscale_ok;
}

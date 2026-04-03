package com.github.pangolin.routing.acceptor.tun.net.handler.tcp.sock;

/**
 * @see <a href="https://github.com/torvalds/linux/blob/master/include/net/inet_sock.h#L69">struct inet_request_sock</a>
 */
public class inet_request_sock extends request_sock {
    public int mss;
    public int snd_wscale;
    public int rcv_wscale;
    public boolean wscale_ok;
}

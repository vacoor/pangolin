package com.github.pangolin.routing.acceptor.tun.net.handler.tcp.sock;

/**
 * @see <a href="https://github.com/torvalds/linux/blob/master/include/net/sock.h#L354">struct sock</a>
 */
public abstract class Sock extends SockCommon {

    public int sk_err;
    public int sk_err_soft;

    public int sk_shutdown;

}

package com.github.pangolin.routing.acceptor.tun.net.handler.tcp.internal;

import com.github.pangolin.routing.acceptor.tun.net.handler.tcp.core.request_sock_ops;

/**
 * @see <a href="https://github.com/torvalds/linux/blob/master/include/net/request_sock.h#L51">struct request_sock</a>
 */
public class request_sock extends SockCommon {

    public Runnable rsk_timer;
    public request_sock_ops rsk_ops;

    public Sock sk;

    public long timeout;

}

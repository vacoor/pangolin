package com.github.pangolin.routing.acceptor.tun.net.handler.tcp.internal;

/**
 * @see <a href="https://github.com/torvalds/linux/blob/master/include/net/inet_timewait_sock.h#L33">inet_timewait_sock</a>
 */
public class inet_timewait_sock extends SockCommon {

    public Runnable tw_timer;

}

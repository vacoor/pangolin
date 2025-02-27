package com.github.pangolin.routing.server.tun.adapter.darwin.jna;

/**
 * @see <a href="https://github.com/torvalds/linux/blob/master/include/linux/socket.h">socket.h</a>
 */
public interface Socket {

    /**
     * stream socket.
     */
    int SOCK_STREAM = 1;

    /**
     * datagram socket.
     */
    int SOCK_DGRAM = 2;

    /**
     * raw-protocol interface.
     */
    int SOCK_RAW = 3;


    /**
     * unspecified.
     */
    int AF_UNSPEC = 0;

    /**
     * IPv4.
     */
    int AF_INET = 2;

    /**
     * Internal Routing Protocol.
     */
    int AF_ROUTE = 17;

    /**
     * Link layer interface.
     */
    int AF_LINK = 18;

    /**
     * IPv6.
     */
    int AF_INET6 = 30;

    /**
     * Kernel event messages.
     */
    int AF_SYSTEM = 32;

}

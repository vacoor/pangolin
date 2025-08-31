package com.github.pangolin.routing.acceptor.tun.net.handler.tcp.internal;

public interface TcpConstants {
    int HZ = 1000;

    byte FIN = 0x0001;
    byte SYN = 0x0002;
    byte RST = 0x0004;
    byte PSH = 0x0008;
    byte ACK = 0x0010;
    byte URG = 0x0020;


    int TCPF_ESTABLISHED = 1 << TcpState.TCP_ESTABLISHED.ordinal();
    int TCPF_CLOSE_WAIT = 1 << TcpState.TCP_CLOSE_WAIT.ordinal();
    int TCPF_CLOSE = 1 << TcpState.TCP_CLOSE.ordinal();
    int TCPF_LISTEN = 1 << TcpState.TCP_LISTEN.ordinal();
    int TCPF_SYN_SENT = 1 << TcpState.TCP_SYN_SENT.ordinal();
    int TCPF_SYN_RECV = 1 << TcpState.TCP_SYN_RECV.ordinal();
    int TCPF_LAST_ACK = 1 << TcpState.TCP_LAST_ACK.ordinal();
    int TCPF_CLOSING = 1 << TcpState.TCP_CLOSING.ordinal();


    /**
     * Nagle's algo is disabled.
     *
     * @see <a href="https://github.com/torvalds/linux/blob/master/include/net/tcp.h#L238">TCP_NAGLE_OFF</a>
     */
    int TCP_NAGLE_OFF = 1;

    /**
     * TCP_NAGLE_CORK.
     *
     * @see <a href="https://github.com/torvalds/linux/blob/master/include/net/tcp.h#L239">TCP_NAGLE_CORK</a>
     */
    int TCP_NAGLE_CORK = 2;

    /**
     * TCP_NAGLE_PUSH.
     *
     * @see <a href="https://github.com/torvalds/linux/blob/master/include/net/tcp.h#L240">TCP_NAGLE_PUSH</a>
     */
    int TCP_NAGLE_PUSH = 4;

}

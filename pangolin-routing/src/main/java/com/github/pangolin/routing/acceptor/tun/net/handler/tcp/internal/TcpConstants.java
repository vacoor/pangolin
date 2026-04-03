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

    int DEFAULT_MTU = 1500;
    int MINIMUM_MTU = 576;
    int TCP_MSS_DEFAULT = 536;
    int TCP_MIN_MSS = 88;
    int U8_MAX = 255;
    int U16_MAX = 65535;
    /**
     * Never offer a window over 32767 without using window scaling. Some
     * poor stacks do signed 16bit maths!
     *
     * @see <a href="https://github.com/torvalds/linux/blob/master/include/net/tcp.h#L68">TCP_MAX_WINDOW</a>
     */
    int TCP_MAX_WINDOW = 32767;
    /*
        RFC6298 2.1 initial RTO value.
        https://github.com/torvalds/linux/blob/master/include/net/tcp.h#L152
        */
    int TCP_TIMEOUT_INIT = 1 * HZ;
    int TCP_TIMEOUT_MIN = 2;
    int TCP_TIMEWAIT_LEN = 60 * HZ;
    // https://github.com/torvalds/linux/blob/master/include/net/tcp.h#L86
    int TCP_MAX_QUICKACKS = 16;

    // https://github.com/torvalds/linux/blob/master/include/net/sock.h#L1472
    int RCV_SHUTDOWN = 1;
    int SEND_SHUTDOWN = 2;
    int SHUTDOWN_MASK = 3;

    // No options.
    int SIZE_OF_TCP_HDR = 20;
    // https://github.com/torvalds/linux/blob/master/include/net/tcp.h#L160
    int TCP_RESOURCE_PROBE_INTERVAL = HZ / 2;
    // https://github.com/torvalds/linux/blob/master/include/net/tcp.h#L243
    /* TCP initial congestion window as per rfc6928 */
    int TCP_INIT_CWND = 10;
    /**
     * @see <a href="https://github.com/torvalds/linux/blob/master/include/net/tcp.h#L1327">TCP_INFINITE_SSTHRESH</a>
     */
    int TCP_INFINITE_SSTHRESH = 0x7fffffff;
    byte TCP_MAX_WSCALE = 14;
    int RTAX_WINDOW = 1;
    int RTAX_INITRWND = 2;
}

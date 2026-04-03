package com.github.pangolin.routing.acceptor.tun.net.handler.tcp.internal;

import static com.github.pangolin.routing.acceptor.tun.net.handler.tcp.internal.TcpConstants.HZ;

public final class TcpConstant {
    // https://github.com/torvalds/linux/blob/master/include/net/tcp.h#L146
    public static final int TCP_RTO_MAX = 120 * HZ;
    public static final int TCP_RTO_MIN = HZ / 5;

    public static final int TCP_DELACK_MIN = HZ / 25;
    public static final int TCP_DELACK_MAX = HZ / 5;
    public static final int TCP_ATO_MIN = HZ / 25;
}

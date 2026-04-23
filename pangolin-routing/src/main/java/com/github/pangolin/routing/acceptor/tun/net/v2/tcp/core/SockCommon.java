package com.github.pangolin.routing.acceptor.tun.net.v2.tcp.core;

/**
 * 套接字族根类 — v2 统一 {@link TcpSock}(完整 ESTABLISHED 态)、
 * {@link TcpRequestSock}(半连接态)和 {@link com.github.pangolin.routing.acceptor.tun.net.v2.tcp.core.TcpTimewaitSock}
 * (TIME_WAIT 迷你态)为共同基类,使 {@link SegmentDispatcher#__inet_lookup_skb} 能返回统一类型,
 * 对应 Linux {@code struct sock_common} 的角色。
 */
public abstract class SockCommon {
    private final FourTuple fourTuple;

    protected SockCommon(FourTuple fourTuple) {
        this.fourTuple = fourTuple;
    }

    public FourTuple fourTuple() {
        return fourTuple;
    }

    public abstract TcpConnectionState state();

    public abstract void state(TcpConnectionState state);
}

package com.github.pangolin.routing.acceptor.tun.net.v2.tcp.core;

import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.core.TcpConnection;

public interface CongestionControl {

    int cwnd(TcpConnection conn);

    boolean isInRecovery(TcpConnection conn);

    void onAck(TcpConnection conn, int ackedSegs, boolean newAck);

    void onTimeout(TcpConnection conn);
}

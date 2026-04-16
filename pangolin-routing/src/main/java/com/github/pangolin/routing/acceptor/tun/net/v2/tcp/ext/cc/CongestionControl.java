package com.github.pangolin.routing.acceptor.tun.net.v2.tcp.ext.cc;

import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.connection.TcpConnection;

public interface CongestionControl {

    int cwnd(TcpConnection conn);

    boolean isInRecovery(TcpConnection conn);

    void onAck(TcpConnection conn, int ackedSegs, boolean newAck);

    void onTimeout(TcpConnection conn);
}

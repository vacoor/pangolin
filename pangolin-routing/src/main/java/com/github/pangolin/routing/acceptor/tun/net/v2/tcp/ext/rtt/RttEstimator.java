package com.github.pangolin.routing.acceptor.tun.net.v2.tcp.ext.rtt;

import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.connection.TcpConnection;

public interface RttEstimator {

    long rtoMs(TcpConnection conn);

    void addSample(TcpConnection conn, long measuredRttUs);

    void backoff(TcpConnection conn);

    void resetBackoff(TcpConnection conn);
}

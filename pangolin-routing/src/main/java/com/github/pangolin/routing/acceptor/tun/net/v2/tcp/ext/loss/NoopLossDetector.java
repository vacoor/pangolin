package com.github.pangolin.routing.acceptor.tun.net.v2.tcp.ext.loss;

import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.connection.TcpConnection;

/**
 * No-op loss detector: no TLP probes, no RACK reordering detection.
 */
public final class NoopLossDetector implements LossDetector {

    public static final NoopLossDetector INSTANCE = new NoopLossDetector();

    private NoopLossDetector() {}

    @Override public void init(TcpConnection conn)                         {}
    @Override public void scheduleProbe(TcpConnection conn)                {}
    @Override public void sendProbe(TcpConnection conn)                    {}
    @Override public void onAck(TcpConnection conn, int ackSeq, int flags) {}
    @Override public void onConnectionClosed(TcpConnection conn)           {}
}

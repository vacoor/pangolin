package com.github.pangolin.routing.acceptor.tun.net.v2.tcp.ext.rtt;

import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.connection.TcpConnection;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.internal.TcpConstants;

/**
 * No-op RTT estimator: returns a fixed RTO, ignores all samples.
 * Used when RFC 6298 adaptive RTO is disabled.
 */
public final class NoopRttEstimator implements RttEstimator {

    public static final NoopRttEstimator INSTANCE = new NoopRttEstimator();

    private NoopRttEstimator() {}

    @Override public void init(TcpConnection conn)                   {}
    @Override public void addSample(TcpConnection conn, long rttUs) {}
    @Override public long rtoMs(TcpConnection conn)                  { return TcpConstants.RTO_INIT_MS; }
    @Override public void backoff(TcpConnection conn)                {}
    @Override public void resetBackoff(TcpConnection conn)           {}
    @Override public void onConnectionClosed(TcpConnection conn)     {}
}

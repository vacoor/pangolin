package com.github.pangolin.routing.acceptor.tun.net.v2.tcp.ext.rtt;

import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.connection.TcpConnection;

/**
 * RFC 6298 RTT estimator interface.
 * Per-connection state is stored in {@link TcpConnection#attributes} via a
 * {@link com.github.pangolin.routing.acceptor.tun.net.v2.tcp.connection.ConnectionKey}.
 */
public interface RttEstimator {

    /** Initialise per-connection state. Called once by {@code TcpConnection.Builder.build()}. */
    void init(TcpConnection conn);

    /**
     * Incorporate a new RTT sample (microseconds).
     *
     * <p><b>Karn's Algorithm (RFC 6298 §4)</b>: the caller ({@code TcpIncomingAckHandler}) must
     * supply {@code rttUs = -1} for retransmitted segments. This method must silently skip
     * sampling when {@code rttUs < 0}. Violating this invariant pollutes SRTT/RTTVAR and
     * produces inaccurate RTO estimates.
     *
     * @param rttUs measured RTT in microseconds, or {@code -1} to skip (Karn's rule)
     */
    void addSample(TcpConnection conn, long rttUs);

    /** Current RTO in milliseconds (must be clamped to [RTO_MIN, RTO_MAX]). */
    long rtoMs(TcpConnection conn);

    /** Exponential backoff of RTO after a retransmit timeout (RFC 6298 §5.5). */
    void backoff(TcpConnection conn);

    /** Reset backoff multiplier to 1 (called when a new ACK advances SND.UNA). */
    void resetBackoff(TcpConnection conn);

    /** Release per-connection state. Called from {@link TcpConnection#close()}. */
    void onConnectionClosed(TcpConnection conn);
}

package com.github.pangolin.routing.acceptor.tun.net.v2.tcp.ext.timestamp;

import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.connection.TcpConnection;

/**
 * RFC 7323 TCP Timestamps + PAWS interface.
 */
public interface TcpTimestampExtension {

    /** Initialise per-connection state. */
    void init(TcpConnection conn);

    /** @return true if timestamps are negotiated and active for this connection. */
    boolean isEnabled(TcpConnection conn);

    /** Build the TSval for an outgoing segment (current timestamp value). */
    int buildTsval(TcpConnection conn);

    /**
     * PAWS check (RFC 7323 §5).
     *
     * @param tsval the TSval from the incoming segment
     * @return true if the segment should be discarded (PAWS rejection)
     */
    boolean isPawsRejected(TcpConnection conn, int tsval);

    /** Update ts_recent after accepting a segment (RFC 7323 §5.3). */
    void updateRecent(TcpConnection conn, int tsval);

    /** Release per-connection state. */
    void onConnectionClosed(TcpConnection conn);
}

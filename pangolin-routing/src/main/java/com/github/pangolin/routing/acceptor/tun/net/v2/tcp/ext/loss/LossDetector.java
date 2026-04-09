package com.github.pangolin.routing.acceptor.tun.net.v2.tcp.ext.loss;

import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.connection.TcpConnection;

/**
 * RFC 8985 (RACK/TLP) loss detection interface.
 */
public interface LossDetector {

    /** Initialise per-connection state. */
    void init(TcpConnection conn);

    /** Schedule a Tail Loss Probe if conditions are met. */
    void scheduleProbe(TcpConnection conn);

    /** Send the Tail Loss Probe segment. */
    void sendProbe(TcpConnection conn);

    /**
     * Notify the detector that an ACK was received.
     *
     * @param ackSeq the ACK number from the received segment
     * @param flags  bitmask of ACK flags (implementation-defined)
     */
    void onAck(TcpConnection conn, int ackSeq, int flags);

    /** Release per-connection state. */
    void onConnectionClosed(TcpConnection conn);
}

package com.github.pangolin.routing.acceptor.tun.net.v2.tcp.handshake;

import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.connection.TcpConnection;

/**
 * Handshake completion event carrying the established connection instance.
 */
public final class TcpHandshakeEstablishedEvent {

    private final TcpConnection connection;

    public TcpHandshakeEstablishedEvent(TcpConnection connection) {
        this.connection = connection;
    }

    public TcpConnection connection() {
        return connection;
    }
}

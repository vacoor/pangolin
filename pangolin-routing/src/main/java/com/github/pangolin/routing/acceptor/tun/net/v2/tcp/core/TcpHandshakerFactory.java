package com.github.pangolin.routing.acceptor.tun.net.v2.tcp.core;

import com.github.pangolin.routing.acceptor.tun.net.codec.TcpPacketBuf;

/**
 * Factory for {@link TcpHandshaker} instances, analogous to
 * {@code WebSocketServerHandshakerFactory}.
 * One factory per listener; creates one {@link TcpHandshaker} per new connection.
 */
public final class TcpHandshakerFactory {

    private final TcpConfig config;

    public TcpHandshakerFactory(TcpConfig config) {
        this.config = config;
    }

    /**
     * Create a new {@link TcpHandshaker} for a new connection.
     * Called by the v2 {@code ng} multiplexer on the first SYN.
     *
     * @param synPkt the SYN packet; fields are extracted immediately, no retain needed
     */
    public TcpHandshaker newHandshaker(TcpPacketBuf synPkt) {
        return new TcpHandshaker(synPkt, config);
    }
}

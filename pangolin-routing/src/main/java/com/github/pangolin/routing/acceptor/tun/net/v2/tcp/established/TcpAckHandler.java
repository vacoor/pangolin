package com.github.pangolin.routing.acceptor.tun.net.v2.tcp.established;

import com.github.pangolin.routing.acceptor.tun.net.handler.support.TcpPacketBuf;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.connection.TcpConnection;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.core.TcpAckProcessor;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.core.TcpRetransmitter;

/**
 * Established-phase ACK handler: delegates to {@link TcpAckProcessor} and manages
 * the retransmit timer based on RTX queue state.
 */
public final class TcpAckHandler {

    public static final TcpAckHandler INSTANCE = new TcpAckHandler();

    private TcpAckHandler() {}

    /**
     * Process the ACK field of an incoming segment.
     * Called even for data segments that also carry an ACK.
     */
    public void onAck(TcpConnection conn, TcpPacketBuf pkt) {
        if (!pkt.isAck()) return;

        TcpAckProcessor.INSTANCE.onAck(conn, pkt);

        // Cancel retransmit timer if all data is acknowledged
        if (!conn.sendBuffer().hasRtxPending()) {
            TcpRetransmitter.INSTANCE.cancelRetransmit(conn);
        }
    }
}

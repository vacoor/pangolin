package com.github.pangolin.routing.acceptor.tun.net.v2.tcp.established;

import com.github.pangolin.routing.acceptor.tun.net.handler.support.TcpPacketBuf;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.connection.TcpConnection;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.core.TcpAckProcessor;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.core.TcpRetransmitter;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.internal.TcpSequence;

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
     *
     * <p>RFC 6298 timer management:
     * <ul>
     *   <li>§5.2: cancel retransmit timer when all in-flight data is acknowledged</li>
     *   <li>§5.3: restart retransmit timer when new data is acknowledged but RTX queue not empty</li>
     * </ul>
     */
    public void onAck(TcpConnection conn, TcpPacketBuf pkt) {
        if (!pkt.isAck()) return;

        int prevUna = conn.sndUna();
        TcpAckProcessor.INSTANCE.onAck(conn, pkt);

        if (!conn.sendBuffer().hasRtxPending()) {
            // All in-flight data acknowledged — cancel retransmit timer (RFC 6298 §5.2)
            TcpRetransmitter.INSTANCE.cancelRetransmit(conn);
        } else if (TcpSequence.after(conn.sndUna(), prevUna)) {
            // New data acknowledged but retransmit queue not empty — restart RTO (RFC 6298 §5.3)
            TcpRetransmitter.INSTANCE.scheduleRetransmit(conn);
        }
    }
}

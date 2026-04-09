package com.github.pangolin.routing.acceptor.tun.net.v2.tcp.ext.cc;

import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.connection.TcpConnection;

import java.util.function.Consumer;

/**
 * RFC 5681 congestion control interface.
 *
 * <p>Design constraints:
 * <ul>
 *   <li>RFC 9293 (core) only calls {@link #onAck} and queries {@link #cwnd}.</li>
 *   <li>dupACK counting, {@code CongestionState} transitions, and fast-retransmit
 *       triggering are fully encapsulated inside the implementation.</li>
 *   <li>Retransmit execution is delegated to RFC 9293 ({@code TcpRetransmitter}) via the
 *       {@code retransmitCallback} injected in {@link #init}.</li>
 * </ul>
 */
public interface CongestionControl {

    /** Congestion control algorithm name (e.g. "newreno"). */
    String name();

    /**
     * Initialise per-connection state.
     *
     * @param retransmitCallback invoked by the CC when fast-retransmit is triggered;
     *                           the actual retransmission is performed by RFC 9293 code
     */
    void init(TcpConnection conn, Consumer<TcpConnection> retransmitCallback);

    /**
     * Called for every received ACK.
     *
     * @param ackedSegments  number of segments acknowledged (≥ 1 if {@code sndUnaAdvanced})
     * @param sndUnaAdvanced true if SND.UNA advanced (new data acknowledged);
     *                       false for a pure duplicate ACK
     */
    void onAck(TcpConnection conn, int ackedSegments, boolean sndUnaAdvanced);

    /** Called when the retransmit timer fires (RTO). CC should halve cwnd / ssthresh. */
    void onTimeout(TcpConnection conn);

    /** Current congestion window in segments. */
    int cwnd(TcpConnection conn);

    /**
     * @return true if CC is in RECOVERY or LOSS state (fast-retransmit or RTO recovery).
     *         RFC 9293 uses this to decide whether to suppress new transmissions.
     */
    boolean isInRecovery(TcpConnection conn);

    /** Release per-connection state. Called from {@link TcpConnection#close()}. */
    void onConnectionClosed(TcpConnection conn);
}

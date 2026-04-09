package com.github.pangolin.routing.acceptor.tun.net.v2.tcp.connection;

/**
 * TCP connection state machine states (RFC 9293 §3.3.2).
 * Modelled after {@code Http2Stream.State}: each state carries semantic boolean flags
 * to replace verbose multi-state checks in handler code.
 *
 * <p>States not held by {@link TcpConnection}:
 * <ul>
 *   <li>{@link #LISTEN} — held by the listener socket, not by an individual connection</li>
 *   <li>{@link #SYN_SENT} — active-open only; TUN stack is passive-open, never used</li>
 *   <li>{@link #SYN_RECEIVED} — held by {@code TcpHandshaker} internally</li>
 * </ul>
 * A {@link TcpConnection} is created only after {@code TcpHandshaker.finishHandshake()}
 * completes; its initial state is always {@link #ESTABLISHED}.
 */
public enum TcpConnectionState {

    // RFC 9293 §3.3.2 — 11 standard states
    CLOSED        (false, false),
    LISTEN        (false, false),   // not held by TcpConnection
    SYN_SENT      (false, false),   // not used in passive TUN; kept for RFC9293 completeness
    SYN_RECEIVED  (false, false),   // held by TcpHandshaker, not TcpConnection
    ESTABLISHED   (true,  true),
    FIN_WAIT_1    (false, true),    // local FIN sent; waiting for ACK or simultaneous FIN
    FIN_WAIT_2    (false, true),    // local FIN ACK'd; waiting for remote FIN
    CLOSE_WAIT    (true,  false),   // remote FIN received; waiting for local close()
    CLOSING       (false, false),   // simultaneous close; both FINs in flight
    LAST_ACK      (false, false),   // local FIN sent after CLOSE_WAIT; waiting for ACK
    TIME_WAIT     (false, false);   // 2×MSL wait before final cleanup

    private final boolean canSend;     // local side has not yet sent FIN
    private final boolean canReceive;  // remote side has not yet sent FIN

    TcpConnectionState(boolean canSend, boolean canReceive) {
        this.canSend    = canSend;
        this.canReceive = canReceive;
    }

    /** @return true if the local side can still send data (has not sent FIN) */
    public boolean canSend()    { return canSend; }

    /** @return true if the remote side can still send data (has not sent FIN) */
    public boolean canReceive() { return canReceive; }

    /** @return true if both sides have closed (neither can send) */
    public boolean isFullyClosed() { return !canSend && !canReceive; }
}

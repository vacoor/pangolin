package com.github.pangolin.routing.acceptor.tun.net.v2.tcp.core;

/**
 * TCP connection state machine states (RFC 9293 §3.3.2).
 * Modelled after {@code Http2Stream.State}: each state carries semantic boolean flags
 * to replace verbose multi-state checks in handler code.
 *
 * <p>States not held by {@link TcpSock}:
 * <ul>
 *   <li>{@link #TCP_LISTEN} — held by the listener socket, not by an individual connection</li>
 *   <li>{@link #TCP_SYN_SENT} — active-open only; TUN stack is passive-open, never used</li>
 * </ul>
 *
 * <p>{@link TcpSock} is created from the v2 open-request path after the
 * final ACK passes sequence validation, analogous to Linux {@code tcp_create_openreq_child()}.
 * The connection starts in {@link #TCP_SYN_RECV} and transitions to {@link #TCP_ESTABLISHED}
 * inside the stack state machine, mirroring the {@code TCP_SYN_RECV} case in Linux
 * {@code rcvStateProcess()}.
 */
public enum TcpConnectionState {

    // RFC 9293 §3.3.2 — 11 standard states
    TCP_CLOSED(false, false),
    TCP_LISTEN(false, false),   // not held by TcpSock
    TCP_SYN_SENT(false, false),   // not used in passive TUN; kept for RFC9293 completeness
    TCP_SYN_RECV(false, false),   // child socket created by finishHandshake(); transitions to ESTABLISHED on final ACK
    TCP_ESTABLISHED(true,  true),
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

package com.github.pangolin.routing.acceptor.tun.net.v2.tcp.core;

/**
 * Labels for the write-timer slot in {@link TcpConnectionTimers}.
 * Multiple timer types share the {@code writeTimer} slot; the type field identifies which
 * handler should fire.
 */
public enum TimerType {

    /** Retransmission timer (RFC 6298). Linux: ICSK_TIME_RETRANS. */
    RETRANSMIT,

    /** Delayed ACK timer (RFC 9293 §3.8.6.2.2). Linux: ICSK_TIME_DACK. */
    DELAYED_ACK,

    /** Zero-window probe timer (RFC 9293 §3.8.6.1). Linux: ICSK_TIME_PROBE0. */
    ZERO_WINDOW_PROBE,

    /** Tail Loss Probe timer (RFC 8985). Linux: ICSK_TIME_LOSS_PROBE. */
    TLP_PROBE,

    /** RACK reorder timer (RFC 8985). Linux: ICSK_TIME_REO_TIMEOUT. */
    REORDER_TIMEOUT,

    /**
     * FIN_WAIT_2 timeout (RFC 9293 §3.9.1).
     * If the peer never sends FIN after we ACK'd their SYN of close, the connection
     * occupies resources indefinitely. This timer (default 60 s, cf. Linux tcp_fin_timeout)
     * forces a transition to TIME_WAIT.
     * Uses the {@code keepaliveTimer} slot — matching Linux's keepalive timer slot usage.
     */
    FIN_WAIT_2_TIMEOUT
}

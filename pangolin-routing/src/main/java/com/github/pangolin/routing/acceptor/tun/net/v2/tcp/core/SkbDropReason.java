package com.github.pangolin.routing.acceptor.tun.net.v2.tcp.core;

public class SkbDropReason {
    // ── skb_drop_reason constants ──────────────────────────────────────────
    // Mirrors Linux enum skb_drop_reason (include/net/dropreason-core.h).
    // Values sourced from v1 TcpDropReason (handler/tcp/internal).

    /** Reason not specified. */
    public static final int SKB_DROP_REASON_NOT_SPECIFIED           =  0;
    /** Packet accepted — do not drop. */
    public static final int SKB_NOT_DROPPED_YET                     =  0;
    /** TCP flag combination is invalid for this state. */
    public static final int SKB_DROP_REASON_TCP_FLAGS               =  1;
    /** Valid RST received — connection aborted. */
    public static final int SKB_DROP_REASON_TCP_RESET               =  2;
    /** Data sent while the receive window is closed. */
    public static final int SKB_DROP_REASON_TCP_ZEROWINDOW          =  3;
    /** Segment fully below rcv_nxt — already received. */
    public static final int SKB_DROP_REASON_TCP_OLD_DATA            =  4;
    /** Segment crosses/exceeds the current receive window. */
    public static final int SKB_DROP_REASON_TCP_OVERWINDOW          =  5;
    /** Socket already transitioned to TCP_CLOSE. */
    public static final int SKB_DROP_REASON_TCP_CLOSE               =  6;
    /** No socket matched the packet's four-tuple. */
    public static final int SKB_DROP_REASON_NO_SOCKET               =  7;
    /** Data arrived after the receiver decided to abort. */
    public static final int SKB_DROP_REASON_TCP_ABORT_ON_DATA       =  8;
    /** ACK field acknowledges data we never sent. */
    public static final int SKB_DROP_REASON_TCP_ACK_UNSENT_DATA     =  9;
    /** ACK is old but within plausible window (old_ack path). */
    public static final int SKB_DROP_REASON_TCP_OLD_ACK             = 10;
    /** ACK is too old — outside any plausible window (blind injection). */
    public static final int SKB_DROP_REASON_TCP_TOO_OLD_ACK         = 11;
    /** PAWS check failed on a non-ACK segment (RFC 7323 §5). */
    public static final int SKB_DROP_REASON_TCP_RFC7323_PAWS        = 36;
    /** PAWS check failed on a pure ACK (disordered-ACK exemption path). */
    public static final int SKB_DROP_REASON_TCP_RFC7323_PAWS_ACK    = 37;
    /** SEQ is before RCV.WUP — segment is fully in the past. */
    public static final int SKB_DROP_REASON_TCP_OLD_SEQUENCE        = 41;
    /** SEQ/end_seq is beyond the current receive window. */
    public static final int SKB_DROP_REASON_TCP_INVALID_SEQUENCE    = 42;
    /** end_seq is invalid (wraps past seq or beyond window). */
    public static final int SKB_DROP_REASON_TCP_INVALID_END_SEQUENCE = 43;
    /** SYN received in an established/closing state — challenge-ACK issued. */
    public static final int SKB_DROP_REASON_TCP_INVALID_SYN         = 46;

}

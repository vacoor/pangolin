package com.github.pangolin.routing.acceptor.tun.net.v2.tcp.core;

import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.connection.TcpConnection;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.connection.TcpSendBuffer.TcpSegmentEntry;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.internal.FourTuple;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.internal.TcpConstants;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.internal.TcpSequence;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.pipeline.TcpConnectionChannel;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.timer.TcpTimerScheduler;
import io.netty.buffer.ByteBuf;

/**
 * TCP segment transmitter: segments application data and sends it within the congestion
 * and receive windows.
 *
 * <p>Stateless — all state lives in {@link TcpConnection}.
 */
public final class TcpSegmenter {

    public static final TcpSegmenter INSTANCE = new TcpSegmenter();

    private TcpSegmenter() {}

    /**
     * Drain the send buffer: segment and transmit pending data within the current
     * congestion window and peer receive window.
     *
     * @param conn     the connection whose send buffer to drain
     * @param mss_now  current MSS (≈ {@code tcp_current_mss()})
     * @param nonagle  Nagle flags ({@link TcpConstants#TCP_NAGLE_OFF} etc.)
     * @param push_one 0 = send as many as allowed; 1 = at most one; 2 = force loss probe
     * @return {@code true} if data is queued but the send window is closed and no segments
     *         are in flight — caller should arm the persist/probe timer;
     *         {@code false} otherwise
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_output.c#L2791">tcp_write_xmit</a>
     */
    public boolean tcp_write_xmit(TcpConnection conn, int mss_now, int nonagle, int push_one) {
        int     sent_pkts       = 0;
        boolean is_cwnd_limited  = false;
        boolean is_rwnd_limited  = false;

        tcp_mstamp_refresh(conn);

        if (push_one == 0) {
            /* Do MTU probing. */
            int mtu = tcp_mtu_probe(conn);
            if (mtu == 0) {
                return false;
            } else if (mtu > 0) {
                sent_pkts = 1;
            }
        }

        final int max_segs = tcp_tso_segs(conn, mss_now);
        ByteBuf skb;
        while ((skb = conn.sendBuffer().peekWrite()) != null) {
            if (tcp_pacing_check(conn)) {
                break;
            }

            int cwnd_quota = tcp_cwnd_test(conn);
            if (cwnd_quota == 0) {
                if (push_one == 2) {
                    /* Force out a loss probe pkt. */
                    cwnd_quota = 1;
                } else {
                    is_cwnd_limited = true;
                    break;
                }
            }
            cwnd_quota = Math.min(cwnd_quota, max_segs);

            final int tso_segs = tcp_set_skb_tso_segs(skb, mss_now);
            if (!tcp_snd_wnd_test(conn, skb, mss_now)) {
                is_rwnd_limited = true;
                break;
            }

            if (tso_segs == 1) {
                if (!tcp_nagle_test(conn, skb, mss_now,
                        tcp_skb_is_last(conn, skb) ? nonagle : TcpConstants.TCP_NAGLE_PUSH)) {
                    break;
                }
            } else {
                // FIXME: TSO deferral not implemented
                if (push_one == 0 && tcp_tso_should_defer(conn, skb, is_cwnd_limited, is_rwnd_limited, max_segs)) {
                    break;
                }
            }

            int limit = mss_now;
            if (tso_segs > 1 && !tcp_urg_mode(conn)) {
                limit = tcp_mss_split_point(conn, skb, mss_now, cwnd_quota, nonagle);
            }

            if (skb.readableBytes() > limit && tso_fragment(conn, skb, limit, mss_now)) {
                break;
            }
            if (tcp_small_queue_check(conn, skb, push_one)) {
                break;
            }

            ByteBuf payload = nextSegment(conn, limit);
            if (payload == null) break;

            if (tcp_transmit_skb(conn, payload) != 0) {
                payload.release();
                break;
            }

            /*
             * Advance the send_head.  This one is sent out.
             * This call will increment packets_out.
             */
            tcp_event_new_data_sent(conn, payload);
            tcp_minshall_update(conn, mss_now, payload);
            sent_pkts += tcp_skb_pcount(payload);
            payload.release();  // release our slice; RTX entry holds its own retainedSlice

            if (push_one != 0) {
                break;
            }
        }

        is_cwnd_limited |= (conn.packetsOut() >= conn.congestionControl().cwnd(conn));
        if (sent_pkts != 0 || is_cwnd_limited) {
            tcp_cwnd_validate(is_cwnd_limited);
        }
        if (sent_pkts != 0) {
            if (tcp_in_cwnd_reduction(conn)) {
                // tp->prr_out += sent_pkts;
            }
            /* Send one loss probe per tail loss episode. */
            if (push_one != 2) {
                // tcp_schedule_loss_probe(conn, false);
            }
            return false;
        }
        return conn.packetsOut() == 0 && conn.sendBuffer().hasDataToSend();
    }

    // ── tcp_write_xmit sub-functions ─────────────────────────────────────

    /**
     * Refresh the connection's cached clock timestamp.
     * Mirrors Linux {@code tcp_mstamp_refresh()} (tcp_output.c).
     *
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_output.c#L55">tcp_mstamp_refresh</a>
     */
    private void tcp_mstamp_refresh(TcpConnection conn) {
        // TODO: update tp->tcp_clock_cache / tp->tcp_mstamp for pacing and RTT stamping
    }

    /**
     * MTU probing — not implemented.
     *
     * @return -1 (not implemented); 0 = early-return false; >0 = MTU probe sent
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_output.c#L2434">tcp_mtu_probe</a>
     */
    private int tcp_mtu_probe(TcpConnection conn) {
        // XXX NOT IMPLEMENTED
        return -1;
    }

    /**
     * Maximum number of TSO segments for this connection.
     * Simplified: TSO is not implemented; returns {@link Integer#MAX_VALUE} so
     * {@code cwnd_quota} is never artificially capped by TSO limits.
     *
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_output.c#L2040">tcp_tso_segs</a>
     */
    private int tcp_tso_segs(TcpConnection conn, int mss_now) {
        // TODO: proper GSO/TSO segment count
        return Integer.MAX_VALUE;
    }

    /**
     * Pacing gate: returns {@code true} if the segment must be held back due to
     * pacing constraints.  Pacing is not implemented; always returns {@code false}.
     *
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_output.c#L2769">tcp_pacing_check</a>
     */
    private boolean tcp_pacing_check(TcpConnection conn) {
        // TODO: compare tp->tcp_wstamp_ns with tp->tcp_clock_cache
        return false;
    }

    /**
     * Mirrors Linux {@code tcp_cwnd_test()}: returns the number of additional
     * segments allowed by the congestion window, or 0 if the window is full.
     *
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_output.c#L2303">tcp_cwnd_test</a>
     */
    private int tcp_cwnd_test(TcpConnection conn) {
        int cwnd      = conn.congestionControl().cwnd(conn);
        int in_flight = conn.sendBuffer().rtxQueueSize();
        return Math.max(0, cwnd - in_flight);
    }

    /**
     * Number of MSS-sized segments in {@code skb}.
     * Simplified: always 1 (no TSO/GSO).
     *
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_output.c#L1614">tcp_set_skb_tso_segs</a>
     */
    private int tcp_set_skb_tso_segs(ByteBuf skb, int mss_now) {
        return 1;
    }

    /**
     * Mirrors Linux {@code tcp_snd_wnd_test()}: returns {@code true} if the leading
     * MSS-sized chunk of {@code skb} fits within the peer's receive window.
     *
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_output.c#L1985">tcp_snd_wnd_test</a>
     */
    private boolean tcp_snd_wnd_test(TcpConnection conn, ByteBuf skb, int mss_now) {
        // end_seq of what would be sent: sndNxt + min(skb data, mss_now)
        int end_seq = conn.sndNxt() + Math.min(skb.readableBytes(), mss_now);
        // tcp_wnd_end(tp) = snd_una + snd_wnd
        int wnd_end = conn.sndUna() + conn.sndWnd();
        return !TcpSequence.after(end_seq, wnd_end);
    }

    /**
     * Returns {@code true} if {@code skb} is the last segment in the write queue.
     * Simplified — always returns {@code false} (FIXME: check last skb in queue).
     *
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_output.c#L1780">tcp_skb_is_last</a>
     */
    private boolean tcp_skb_is_last(TcpConnection conn, ByteBuf skb) {
        // FIXME: return conn.sendBuffer().writeQueueSize() == 1
        return false;
    }

    /**
     * Mirrors Linux {@code tcp_nagle_test()}: returns {@code true} if the segment
     * may be sent under the current Nagle / push constraints.
     *
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_output.c#L1785">tcp_nagle_test</a>
     */
    private boolean tcp_nagle_test(TcpConnection conn, ByteBuf skb, int mss_now, int nonagle) {
        if ((nonagle & (TcpConstants.TCP_NAGLE_OFF | TcpConstants.TCP_NAGLE_PUSH)) != 0) {
            return true;
        }
        // Standard Nagle: allow only if segment fills MSS or nothing is in flight
        return skb.readableBytes() >= mss_now || conn.packetsOut() == 0;
    }

    /**
     * TSO deferral check — not implemented; always returns {@code false}.
     *
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_output.c#L2065">tcp_tso_should_defer</a>
     */
    private boolean tcp_tso_should_defer(TcpConnection conn, ByteBuf skb,
                                          boolean is_cwnd_limited, boolean is_rwnd_limited,
                                          int max_segs) {
        // FIXME: not implemented
        return false;
    }

    /**
     * Urgent-mode check — not implemented; always returns {@code false}.
     *
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_output.c#L1993">tcp_urg_mode</a>
     */
    private boolean tcp_urg_mode(TcpConnection conn) {
        return false;
    }

    /**
     * Compute the largest amount of data that may be sent in a single TSO segment,
     * bounded by the MSS, send window, and Nagle constraints.
     * Simplified: always returns {@code mss_now}.
     *
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_output.c#L1741">tcp_mss_split_point</a>
     */
    private int tcp_mss_split_point(TcpConnection conn, ByteBuf skb, int mss_now,
                                     int cwnd_quota, int nonagle) {
        return mss_now;
    }

    /**
     * Fragment {@code skb} at {@code limit} bytes for TSO — not implemented;
     * always returns {@code false} (no fragment, keep going).
     *
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_output.c#L1559">tso_fragment</a>
     */
    private boolean tso_fragment(TcpConnection conn, ByteBuf skb, int limit, int mss_now) {
        return false;
    }

    /**
     * Small-queue check: prevents a single flow from flooding the NIC TX queue.
     * Not implemented; always returns {@code false}.
     *
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_output.c#L2374">tcp_small_queue_check</a>
     */
    private boolean tcp_small_queue_check(TcpConnection conn, ByteBuf skb, int push_one) {
        return false;
    }

    /**
     * Dequeues one segment of at most {@code limit} bytes from the write buffer,
     * also bounded by the remaining send window.
     * Returns {@code null} if the window is exhausted.
     * Mirrors the per-skb slice logic inside the Linux {@code tcp_write_xmit} loop.
     */
    private ByteBuf nextSegment(TcpConnection conn, int limit) {
        ByteBuf head = conn.sendBuffer().peekWrite();
        if (head == null) return null;
        int window = conn.sndUna() + conn.sndWnd() - conn.sndNxt();
        int segLen = Math.min(head.readableBytes(), Math.min(limit, window));
        if (segLen <= 0) return null;
        ByteBuf segment = head.readRetainedSlice(segLen);  // advances readerIndex in-place
        if (!head.isReadable()) {
            conn.sendBuffer().pollWrite().release();        // fully consumed — remove + release
        }
        return segment;
    }

    /**
     * Build and transmit one data segment on the wire.
     *
     * <p>Only responsible for header construction and I/O — no TCP state is updated here.
     * The caller must invoke {@link #tcp_event_new_data_sent} immediately after a successful
     * return to advance SND.NXT, enqueue in the RTX queue, and arm the RTO timer.
     *
     * @return 0 on success, non-zero on send failure
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_output.c#L1399">tcp_transmit_skb</a>
     */
    private int tcp_transmit_skb(TcpConnection conn, ByteBuf payload) {
        FourTuple ft = ((TcpConnectionChannel) conn.channel()).fourTuple();
        int payLen   = payload.readableBytes();

        ByteBuf buf = TcpPacketBuilder.buildRaw(
                ft.dstAddrBytes(), ft.dstPort(),
                ft.srcAddrBytes(), ft.srcPort(),
                conn.sndNxt(), conn.rcvNxt(),
                0x18 /* PSH+ACK */, selectAdvertisedWindow(conn),
                null, payload, payLen);

        ((TcpConnectionChannel) conn.channel()).writeRaw(buf);

        // Mirrors Linux tcp_event_ack_sent(): every data segment carries the ACK flag,
        // so the piggybacked ACK covers any pending acknowledgement.
        // Clear ACK_SCHED | ACK_TIMER and cancel the delayed-ACK timer.
        // Linux does exactly this inside __tcp_transmit_skb → tcp_event_ack_sent →
        //   inet_csk_clear_xmit_timer(ICSK_TIME_DACK).
        if (conn.hasAckPending(TcpConstants.ACK_SCHED | TcpConstants.ACK_TIMER)) {
            if (conn.hasAckPending(TcpConstants.ACK_TIMER)) {
                TcpTimerScheduler.INSTANCE.cancelDelayedAck(conn);
            }
            conn.clearAckPending(TcpConstants.ACK_SCHED | TcpConstants.ACK_TIMER);
        }

        return 0;
    }

    /**
     * Update send-path state after a segment has been handed to the NIC.
     *
     * <p>Mirrors Linux {@code tcp_event_new_data_sent()}:
     * <ol>
     *   <li>Advance {@code SND.NXT} by the payload length.</li>
     *   <li>Enqueue a retained copy in the RTX (retransmit) queue.</li>
     *   <li>Increment {@code packets_out}.</li>
     *   <li>Arm the RTO timer if this is the first segment in flight (RFC 6298 §5.1).</li>
     * </ol>
     *
     * <p>Must be called immediately after {@link #tcp_transmit_skb} returns 0, while
     * {@code SND.NXT} still points to the start of {@code payload}.
     *
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_output.c#L2595">tcp_event_new_data_sent</a>
     */
    private void tcp_event_new_data_sent(TcpConnection conn, ByteBuf payload) {
        final int  seq       = conn.sndNxt();
        final int  payLen    = payload.readableBytes();
        final long sentTime  = System.nanoTime() / 1_000L;

        // tp->snd_nxt = TCP_SKB_CB(skb)->end_seq
        conn.sndNxt(seq + payLen);

        // tcp_rbtree_insert: move skb from write queue to RTX queue
        TcpSegmentEntry entry = new TcpSegmentEntry(
                payload.retainedSlice(), seq, payLen, false, sentTime);
        final boolean startRto = !conn.sendBuffer().hasRtxPending();
        conn.sendBuffer().enqueueRtx(entry);

        // tp->packets_out += tcp_skb_pcount(skb)
        conn.incrementPacketsOut();

        // RFC 6298 §5.1: if (prior_packets == 0 || icsk_pending == ICSK_TIME_LOSS_PROBE)
        //                     tcp_rearm_rto(sk)
        if (startRto) {
            TcpRetransmitter.INSTANCE.scheduleRetransmit(conn);
        }
    }

    /**
     * Minshall's extension to Nagle: update {@code snd_sml} if the just-sent segment
     * is sub-MSS, to prevent the algorithm from being too conservative.
     * Requires {@code snd_sml} state on {@link TcpConnection} — not yet added.
     *
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_output.c#L566">tcp_minshall_update</a>
     */
    private void tcp_minshall_update(TcpConnection conn, int mss_now, ByteBuf skb) {
        // TODO: if (skb.readableBytes() < tcp_skb_pcount(skb) * mss_now) conn.sndSml(snd_nxt)
    }

    /**
     * Number of MSS-sized segments represented by {@code skb}.
     * Always 1 — no TSO/GSO.
     *
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_output.c#L1610">tcp_skb_pcount</a>
     */
    private int tcp_skb_pcount(ByteBuf skb) {
        return 1;
    }

    /**
     * Returns {@code true} if the connection is currently in congestion-window
     * reduction (fast recovery / SACK recovery).  Not yet implemented.
     *
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_output.c#L2745">tcp_in_cwnd_reduction</a>
     */
    private boolean tcp_in_cwnd_reduction(TcpConnection conn) {
        return false;
    }

    /**
     * Mirrors Linux {@code tcp_cwnd_validate()}: update cwnd utilisation stats.
     * Not yet implemented.
     *
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_output.c#L2359">tcp_cwnd_validate</a>
     */
    private void tcp_cwnd_validate(boolean is_cwnd_limited) {
        // TODO: update tp->is_cwnd_limited, tp->max_packets_out
    }

    // ── Public send helpers ───────────────────────────────────────────────

    /**
     * Send a FIN segment (FIN+ACK).
     * Advances SND.NXT by 1 to account for the FIN sequence number.
     */
    public void sendFin(TcpConnection conn) {
        FourTuple ft = ((TcpConnectionChannel) conn.channel()).fourTuple();
        int seq = conn.sndNxt();
        int ack = conn.rcvNxt();
        int wnd = selectAdvertisedWindow(conn);

        ByteBuf buf = TcpPacketBuilder.buildRaw(
                ft.dstAddrBytes(), ft.dstPort(),
                ft.srcAddrBytes(), ft.srcPort(),
                seq, ack, 0x11 /* FIN+ACK */,
                wnd, null, null, 0);

        conn.sndNxt(conn.sndNxt() + 1);   // FIN consumes one sequence number

        ((TcpConnectionChannel) conn.channel()).writeRaw(buf);
    }

    /**
     * Send a pure ACK (no data).
     */
    public void sendAck(TcpConnection conn) {
        FourTuple ft = ((TcpConnectionChannel) conn.channel()).fourTuple();
        int wnd = selectAdvertisedWindow(conn);
        ByteBuf buf = TcpPacketBuilder.buildRaw(
                ft.dstAddrBytes(), ft.dstPort(),
                ft.srcAddrBytes(), ft.srcPort(),
                conn.sndNxt(), conn.rcvNxt(),
                0x10 /* ACK */,
                wnd, null, null, 0);
        ((TcpConnectionChannel) conn.channel()).writeRaw(buf);
    }

    /**
     * Send a RST+ACK.
     */
    public void sendRstAck(TcpConnection conn) {
        FourTuple ft = ((TcpConnectionChannel) conn.channel()).fourTuple();
        ByteBuf buf = TcpPacketBuilder.buildRaw(
                ft.dstAddrBytes(), ft.dstPort(),
                ft.srcAddrBytes(), ft.srcPort(),
                conn.sndNxt(), conn.rcvNxt(),
                0x14 /* RST+ACK */,
                0, null, null, 0);
        ((TcpConnectionChannel) conn.channel()).writeRaw(buf);
    }

    private static int selectAdvertisedWindow(TcpConnection conn) {
        // Linux tcp_select_window updates rcv_wup when selecting the advertised window.
        conn.rcvWup(conn.rcvNxt());
        int wnd = conn.rcvWnd() >> conn.rcvWscale();
        return Math.min(Math.max(wnd, 0), 65535);
    }
}

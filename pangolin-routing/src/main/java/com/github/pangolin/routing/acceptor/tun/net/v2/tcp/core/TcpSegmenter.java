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
 * <p>Follows Linux's model exactly: the write queue stores {@link TcpSegmentEntry} objects
 * with sequence numbers already assigned at enqueue time (via {@link TcpConnection#queueWrite}).
 * {@link #tcp_transmit_skb} reads the seq directly from the entry (mirroring Linux reading
 * {@code TCP_SKB_CB(skb)->seq}).  {@link #tcp_event_new_data_sent} moves the same entry
 * object from the write queue to the RTX queue — no new allocation at transmission time.
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
        int     sent_pkts      = 0;
        boolean is_cwnd_limited = false;
        boolean is_rwnd_limited = false;

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
        TcpSegmentEntry skb;
        while ((skb = conn.tcpSendHead()) != null) {
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

            /*
             * Argh, we hit an empty skb(), presumably a thread is sleeping in
             * sendmsg()/sk_stream_wait_memory(). We do not want to send a pure-ack
             * packet and have a strange looking rtx queue with empty packet(s).
             * Mirrors Linux: if (TCP_SKB_CB(skb)->seq == TCP_SKB_CB(skb)->end_seq)
             */
            if (skb.dataLen() == 0 && !skb.isFin()) {
                break;
            }

            int limit = mss_now;
            if (tso_segs > 1 && !tcp_urg_mode(conn)) {
                limit = tcp_mss_split_point(conn, skb, mss_now, cwnd_quota, nonagle);
            }

            if (skb.dataLen() > limit) {
                if (tcp_fragment(conn, skb, limit, mss_now)) {
                    break;
                }
                /* tcp_fragment replaced the head entry — re-peek the new (smaller) head. */
                skb = conn.tcpSendHead();
                if (skb == null) {
                    break;
                }
            }

            if (tcp_small_queue_check(conn, skb, push_one)) {
                break;
            }

            // FIXME
            if (0 != tcp_transmit_skb(conn, skb)) {
                break;
            }

            /*
             * Advance the send_head.  This one is sent out.
             * This call will increment packets_out.
             * Mirrors Linux: tcp_event_new_data_sent moves skb from sk_write_queue to tcp_rtx_queue.
             */
            tcp_event_new_data_sent(conn, skb);
            tcp_minshall_update(conn, mss_now, skb);
            sent_pkts += tcp_skb_pcount(skb);

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
        return conn.packetsOut() == 0 && conn.tcpSendHead() != null;
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
    private int tcp_set_skb_tso_segs(TcpSegmentEntry skb, int mss_now) {
        return 1;
    }

    /**
     * Mirrors Linux {@code tcp_snd_wnd_test()}: returns {@code true} if the leading
     * MSS-sized chunk of {@code skb} fits within the peer's receive window.
     *
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_output.c#L1985">tcp_snd_wnd_test</a>
     */
    private boolean tcp_snd_wnd_test(TcpConnection conn, TcpSegmentEntry skb, int mss_now) {
        // end_seq of what would be sent: skb.startSeq + min(skb data, mss_now)
        int end_seq = skb.startSeq() + Math.min(skb.dataLen(), mss_now);
        // tcp_wnd_end(tp) = snd_una + snd_wnd
        int wnd_end = conn.sndUna() + conn.sndWnd();
        return !TcpSequence.after(end_seq, wnd_end);
    }

    /**
     * Returns {@code true} if {@code skb} is the last segment in the write queue.
     *
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_output.c#L1780">tcp_skb_is_last</a>
     */
    private boolean tcp_skb_is_last(TcpConnection conn, TcpSegmentEntry skb) {
        return conn.sendBuffer().writeQueueSize() == 1;
    }

    /**
     * Mirrors Linux {@code tcp_nagle_test()}: returns {@code true} if the segment
     * may be sent under the current Nagle / push constraints.
     *
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_output.c#L1785">tcp_nagle_test</a>
     */
    private boolean tcp_nagle_test(TcpConnection conn, TcpSegmentEntry skb, int mss_now, int nonagle) {
        if ((nonagle & (TcpConstants.TCP_NAGLE_OFF | TcpConstants.TCP_NAGLE_PUSH)) != 0) {
            return true;
        }
        // Standard Nagle: allow only if segment fills MSS or nothing is in flight
        return skb.dataLen() >= mss_now || conn.packetsOut() == 0;
    }

    /**
     * TSO deferral check — not implemented; always returns {@code false}.
     *
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_output.c#L2065">tcp_tso_should_defer</a>
     */
    private boolean tcp_tso_should_defer(TcpConnection conn, TcpSegmentEntry skb,
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
    private int tcp_mss_split_point(TcpConnection conn, TcpSegmentEntry skb, int mss_now,
                                     int cwnd_quota, int nonagle) {
        return mss_now;
    }

    /**
     * Fragment {@code skb} at {@code limit} bytes: split the head write-queue entry into
     * two entries of {@code limit} and {@code skb.dataLen() - limit} bytes respectively,
     * each with the correct start sequence number.
     *
     * <p>Mirrors Linux {@code tcp_fragment} (tcp_output.c): the original SKB is split into
     * two; the first fragment keeps its original {@code TCP_SKB_CB->seq}, and the second
     * gets {@code seq + limit}.  Both are re-inserted into the write queue in order.
     *
     * @return {@code false} always — caller should re-peek the write queue and continue
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_output.c#L1559">tcp_fragment</a>
     */
    private boolean tcp_fragment(TcpConnection conn, TcpSegmentEntry skb, int limit, int mss_now) {
        ByteBuf origPayload = skb.payload();
        int     origReader  = origPayload.readerIndex();

        ByteBuf headBuf = origPayload.retainedSlice(origReader, limit);
        int     tailLen = skb.dataLen() - limit;
        ByteBuf tailBuf = origPayload.retainedSlice(origReader + limit, tailLen);

        TcpSegmentEntry headEntry = new TcpSegmentEntry(headBuf, skb.startSeq(),         limit,   false,        0L);
        TcpSegmentEntry tailEntry = new TcpSegmentEntry(tailBuf, skb.startSeq() + limit, tailLen, skb.isFin(), 0L);

        /* Remove original entry and release its payload; the two slices above hold their own refs. */
        conn.sendBuffer().pollWrite().release();

        /* Re-insert in order: enqueueWriteFirst(tail) then enqueueWriteFirst(head)
         * so that head is at the front and tail follows immediately. */
        conn.sendBuffer().enqueueWriteFirst(tailEntry);
        conn.sendBuffer().enqueueWriteFirst(headEntry);

        return false;
    }

    /**
     * Small-queue check: prevents a single flow from flooding the NIC TX queue.
     * Not implemented; always returns {@code false}.
     *
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_output.c#L2374">tcp_small_queue_check</a>
     */
    private boolean tcp_small_queue_check(TcpConnection conn, TcpSegmentEntry skb, int push_one) {
        return false;
    }

    /**
     * Build and transmit one data segment on the wire.
     *
     * <p>Mirrors Linux {@code __tcp_transmit_skb()}: builds the TCP/IP packet, writes it to
     * the TUN device, and performs per-segment accounting (ACK piggybacking, stats).
     * {@code rcv_nxt} is taken as an explicit parameter so the retransmitter can pass a
     * snapshot of RCV.NXT rather than the live value — exactly as Linux splits
     * {@code __tcp_transmit_skb} from {@code tcp_transmit_skb}.
     *
     * @return 0 on success, non-zero on send failure
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_output.c#L1290">__tcp_transmit_skb</a>
     */
    private int __tcp_transmit_skb(TcpConnection conn, TcpSegmentEntry skb, int rcv_nxt) {
        if (skb == null) {
            return -1;
        }

        // TODO: pacing timestamp update
        // tp.tcp_wstamp_ns = Math.max(tp.tcp_wstamp_ns, tp.tcp_clock_cache);
        // tp.skb_set_delivery_time(skb, tp.tcp_wstamp_ns, SKB_CLOCK_MONOTONIC);

        /* Build TCP options for the established path (no SYN here). */
        final byte[] rawOptions = tcp_established_options(conn, skb);

        /*
         * Fill addressing fields.  Note the swap: from the TUN device's perspective the
         * packet travels dst→src (the "local" host is the destination of the original flow).
         * Mirrors Linux: skb->srcAddr = tp.ir_loc_addr, skb->dstAddr = tp.ir_rmt_addr.
         */
        final FourTuple ft = ((TcpConnectionChannel) conn.channel()).fourTuple();

        /* Determine TCP flags.
         * Always set ACK; add PSH when carrying data; add FIN when the entry is a FIN. */
        int tcpFlags = 0x10; /* ACK */
        if (skb.dataLen() > 0) {
            tcpFlags |= 0x08; /* PSH */
        }
        if (skb.isFin()) {
            tcpFlags |= 0x01; /* FIN */
        }

        ByteBuf buf = TcpPacketBuilder.buildRaw(
                ft.dstAddrBytes(), ft.dstPort(),
                ft.srcAddrBytes(), ft.srcPort(),
                skb.startSeq(), rcv_nxt,
                tcpFlags, selectAdvertisedWindow(conn),
                rawOptions, skb.payload(), skb.dataLen());

        ((TcpConnectionChannel) conn.channel()).writeRaw(buf);

        /* Every data segment carries the ACK flag, so the piggybacked ACK covers any
         * pending delayed acknowledgement.  Mirrors Linux:
         *   __tcp_transmit_skb → tcp_event_ack_sent → inet_csk_clear_xmit_timer(ICSK_TIME_DACK). */
        tcp_event_ack_sent(conn, rcv_nxt);

        if (skb.dataLen() > 0) {
            tcp_event_data_sent(conn);
            // TODO: tp.data_segs_out += tcp_skb_pcount(skb); tp.bytes_sent += skb.dataLen();
        }

        // TODO: tp.segs_out += tcp_skb_pcount(skb);

        return 0;
    }

    /**
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_output.c#L1486">tcp_transmit_skb</a>
     */
    private int tcp_transmit_skb(TcpConnection conn, TcpSegmentEntry skb) {
        return __tcp_transmit_skb(conn, skb, conn.rcvNxt());
    }

    /**
     * Account for an ACK we sent.
     *
     * <p>If the piggybacked ACK number equals RCV.NXT the acknowledgement is current:
     * clear {@code ACK_SCHED | ACK_TIMER} and cancel the delayed-ACK timer.
     * Mirrors Linux {@code tcp_event_ack_sent()} →
     * {@code inet_csk_clear_xmit_timer(ICSK_TIME_DACK)}.
     *
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_output.c#L182">tcp_event_ack_sent</a>
     */
    private void tcp_event_ack_sent(TcpConnection conn, int rcv_nxt) {
        if (rcv_nxt != conn.rcvNxt()) {
            return;
        }
        // TODO: tcp_dec_quickack_mode(conn);
        if (conn.hasAckPending(TcpConstants.ACK_SCHED | TcpConstants.ACK_TIMER)) {
            if (conn.hasAckPending(TcpConstants.ACK_TIMER)) {
                TcpTimerScheduler.INSTANCE.cancelDelayedAck(conn);
            }
            conn.clearAckPending(TcpConstants.ACK_SCHED | TcpConstants.ACK_TIMER);
        }
    }

    /**
     * Congestion-state accounting after a data segment has been sent.
     * Updates {@code lsndtime} and the ping-pong counter.
     * Not yet implemented.
     *
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_output.c#L163">tcp_event_data_sent</a>
     */
    private void tcp_event_data_sent(TcpConnection conn) {
        // TODO: tp.lsndtime = tcp_jiffies32();
        // TODO: if (now - tp.icsk_ack.lrcvtime < tp.icsk_ack.ato) tp.inet_csk_inc_pingpong_cnt();
    }

    /**
     * Build TCP options for an established connection.
     * Currently returns {@code null} (no options); timestamps will be added here
     * once {@link com.github.pangolin.routing.acceptor.tun.net.v2.tcp.ext.timestamp.TcpTimestampExtension}
     * is wired in.
     *
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_output.c#L633">tcp_established_options</a>
     */
    private byte[] tcp_established_options(TcpConnection conn, TcpSegmentEntry skb) {
        // TODO: include TCP timestamps (TcpTimestampExtension), SACK blocks, etc.
        return null;
    }

    /**
     * Update send-path state after a segment has been handed to the NIC.
     *
     * <p>Mirrors Linux {@code tcp_event_new_data_sent()} exactly:
     * <ol>
     *   <li>Advance {@code SND.NXT} to {@code skb.endSeq()} — mirrors
     *       {@code tp->snd_nxt = TCP_SKB_CB(skb)->end_seq}.</li>
     *   <li>Move the entry from the write queue to the RTX queue — mirrors
     *       {@code __skb_unlink(skb, &sk->sk_write_queue)} +
     *       {@code tcp_rbtree_insert(&sk->tcp_rtx_queue, skb)}.
     *       No new allocation: the same entry object is promoted.</li>
     *   <li>Stamp {@code sentTimeUs} on the entry (was 0 while in write queue).</li>
     *   <li>Increment {@code packets_out}.</li>
     *   <li>Arm the RTO timer if this is the first segment in flight (RFC 6298 §5.1).</li>
     * </ol>
     *
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_output.c#L2595">tcp_event_new_data_sent</a>
     */
    private void tcp_event_new_data_sent(TcpConnection conn, TcpSegmentEntry skb) {
        // tp->snd_nxt = TCP_SKB_CB(skb)->end_seq
        conn.sndNxt(skb.endSeq());

        // __skb_unlink(skb, &sk->sk_write_queue) + tcp_rbtree_insert(&sk->tcp_rtx_queue, skb)
        conn.sendBuffer().pollWrite();   // remove from write queue — skb reference still held here
        final boolean startRto = !conn.sendBuffer().hasRtxPending();
        skb.updateSentTime(System.nanoTime() / 1_000L);
        conn.sendBuffer().enqueueRtx(skb);

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
    private void tcp_minshall_update(TcpConnection conn, int mss_now, TcpSegmentEntry skb) {
        // TODO: if (skb.dataLen() < tcp_skb_pcount(skb) * mss_now) conn.sndSml(snd_nxt)
    }

    /**
     * Number of MSS-sized segments represented by {@code skb}.
     * Always 1 — no TSO/GSO.
     *
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_output.c#L1610">tcp_skb_pcount</a>
     */
    private int tcp_skb_pcount(TcpSegmentEntry skb) {
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

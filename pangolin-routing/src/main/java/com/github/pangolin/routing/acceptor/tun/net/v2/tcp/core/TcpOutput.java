package com.github.pangolin.routing.acceptor.tun.net.v2.tcp.core;

import com.github.pangolin.routing.acceptor.tun.net.handler.support.TcpPacketBuf;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.connection.TcpConnection;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.connection.TcpSendBuffer.TcpSegmentEntry;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.internal.FourTuple;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.internal.TcpConstants;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.internal.TcpSequence;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.pipeline.TcpSockChannel;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.timer.TcpTimerScheduler;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;

import static com.github.pangolin.routing.acceptor.tun.net.handler.tcp.util.TcpClock.tcp_jiffies32;
import static com.github.pangolin.routing.acceptor.tun.net.v2.tcp.internal.TcpConstants.*;

/**
 * TCP output operations — the <b>single point</b> of all outbound packet writes.
 *
 * <p>Mirrors Linux {@code net/ipv4/tcp_output.c}: every packet that leaves via
 * the TUN interface originates here. No other component calls {@code writeRaw()} or
 * {@code writeAndFlush()} directly.
 *
 * <p>Responsibilities by section:
 * <ul>
 *   <li><b>Established-state sends</b> ({@link TcpConnection} available):
 *       {@link #tcp_send_ack}, {@link #tcp_send_challenge_ack},
 *       {@link #tcp_write_xmit}, {@link #tcp_send_fin},
 *       {@link #tcp_send_reset}, {@link #tcp_retransmit_skb}.</li>
 *   <li><b>Handshake sends</b> (no {@link TcpConnection} yet, raw params):
 *       {@link #tcp_send_synack}, {@link #tcp_send_challenge_ack_handshake},
 *       {@link #tcp_send_reset_handshake}.</li>
 *   <li><b>Stateless RST</b> (listen path, no connection):
 *       {@link #tcp_v4_send_reset}.</li>
 * </ul>
 *
 * <p>Stateless — all mutable state lives in {@link TcpConnection}.
 */
public final class TcpOutput {

    public static final TcpOutput INSTANCE = new TcpOutput();

    private TcpOutput() {}

    // ═══════════════════════════════════════════════════════════════════════
    // Established-state sends  (have TcpConnection)
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Send a pure ACK (no data).
     *
     * <p>Mirrors Linux {@code __tcp_send_ack}: builds an SKB that is <em>not</em> placed on the
     * write queue, then transmits immediately. After the write,
     * {@link #tcp_event_ack_sent} is called — clearing {@code ACK_SCHED | ACK_TIMER} and
     * cancelling the delayed-ACK timer — exactly as Linux {@code __tcp_transmit_skb} does on
     * every outbound segment.
     *
     * <p>Seq = {@code SND.NXT} (= {@code tcp_acceptable_seq} for non-URG),
     * Ack = {@code RCV.NXT}.
     *
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_output.c">__tcp_send_ack</a>
     */
    public void tcp_send_ack(TcpConnection conn) {
        final int rcv_nxt = conn.rcvNxt();   // snapshot before write (mirrors Linux rcv_nxt arg)
        FourTuple ft  = fourTuple(conn);
        int       wnd = selectAdvertisedWindow(conn);
        ByteBuf   buf = TcpPacketBuilder.buildRaw(
                ft.dstAddrBytes(), ft.dstPort(),
                ft.srcAddrBytes(), ft.srcPort(),
                conn.sndNxt(), rcv_nxt,
                TCPHDR_ACK, wnd, null, null, 0);
        writeRaw(conn, buf);
        /* Mirrors __tcp_transmit_skb → tcp_event_ack_sent → inet_csk_clear_xmit_timer(DACK). */
        tcp_event_ack_sent(conn, rcv_nxt);
    }

    /**
     * Send a challenge ACK (RFC 5961 §7 / RFC 9293 §3.5.3 ACK throttling).
     *
     * <p>Mirrors Linux {@code tcp_send_challenge_ack} (tcp_input.c):
     * <ol>
     *   <li><b>Per-socket rate limit</b> — mirrors {@code __tcp_oow_rate_limited}:
     *       if {@code last_oow_ack_time} is set and fewer than
     *       {@link TcpConstants#INVALID_ACK_RATELIMIT_MS} ms have elapsed, drop silently.</li>
     *   <li><b>Host-wide rate limit</b> (sysctl {@code tcp_challenge_ack_limit}) — not
     *       implemented; see TODO below.</li>
     *   <li>Send a pure ACK via {@link #tcp_send_ack}.</li>
     * </ol>
     *
     * <p>{@code accecn_reflector}: reserved for AccECN support (RFC 9331); not implemented.
     *
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_input.c">tcp_send_challenge_ack</a>
     */
    public void tcp_send_challenge_ack(TcpConnection conn, boolean accecn_reflector) {
        /* ── Step 1: per-socket rate limit — mirrors __tcp_oow_rate_limited ── */
        long now     = tcp_jiffies32();
        long lastOow = conn.lastOowAckTimeMs();
        if (lastOow != 0) {
            long elapsed = now - lastOow;
            if (elapsed >= 0 && elapsed < TcpConstants.INVALID_ACK_RATELIMIT_MS) {
                return;  /* rate-limited: not yet */
            }
        }
        conn.lastOowAckTimeMs(now);

        /* ── Step 2: host-wide rate limit ───────────────────────────────────
         * TODO: implement sysctl tcp_challenge_ack_limit (global token bucket).
         *       Linux: if (net->ipv4.tcp_challenge_count > 0) { count--; send; }
         */

        tcp_send_ack(conn);
    }

    /**
     * Send a RST+ACK immediately, bypassing the write queue.
     *
     * <p>Mirrors Linux {@code tcp_send_active_reset} (tcp_output.c):
     * <ul>
     *   <li>RST is never placed on the write queue — sent out-of-band.</li>
     *   <li>RST does not consume a sequence number ({@code snd_nxt} is not advanced).</li>
     *   <li>{@code tcp_mstamp_refresh} is called before transmit, as Linux does.</li>
     * </ul>
     *
     * <p>Seq = {@code SND.NXT}, Ack = {@code RCV.NXT}, flags = RST+ACK, window = 0.
     *
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_output.c">tcp_send_active_reset</a>
     */
    public void tcp_send_reset(TcpConnection conn) {
        tcp_mstamp_refresh(conn);   // mirrors tcp_send_active_reset → tcp_mstamp_refresh(tp)
        FourTuple ft  = fourTuple(conn);
        ByteBuf   buf = TcpPacketBuilder.buildRaw(
                ft.dstAddrBytes(), ft.dstPort(),
                ft.srcAddrBytes(), ft.srcPort(),
                conn.sndNxt(), conn.rcvNxt(),
                TCPHDR_RST | TCPHDR_ACK, 0, null, null, 0);
        writeRaw(conn, buf);
    }

    /**
     * Retransmit the oldest unacknowledged segment.
     *
     * <p>Mirrors Linux {@code tcp_retransmit_skb} / {@code __tcp_retransmit_skb}
     * (tcp_output.c):
     * <ol>
     *   <li><b>Send-window guard</b> — mirrors {@code __tcp_retransmit_skb}:
     *       {@code if (!before(skb->seq, tcp_wnd_end(tp))) return -EAGAIN}.
     *       If the receiver has shrunk its window such that {@code oldest.startSeq ≥ SND.UNA + SND.WND},
     *       the segment is outside the window and must not be retransmitted.</li>
     *   <li><b>Mark retransmitted</b> — sets the Karn flag so RTT samples are suppressed for
     *       this segment ({@code TCPCB_RETRANS}).</li>
     *   <li><b>Stamp sent-time</b> — updates {@code skb->skb_mstamp} for future RTT measurement.</li>
     *   <li><b>Transmit</b> — via {@link #__tcp_transmit_skb}, which calls
     *       {@link #tcp_event_ack_sent} to piggy-back the ACK flag and clear the delayed-ACK state.</li>
     * </ol>
     *
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_output.c">tcp_retransmit_skb</a>
     */
    public void tcp_retransmit_skb(TcpConnection conn) {
        TcpSegmentEntry oldest = conn.sendBuffer().peekRtx();
        if (oldest == null) return;

        /* __tcp_retransmit_skb: if receiver has shrunk window, do not retransmit.
         * Mirrors: if (!before(TCP_SKB_CB(skb)->seq, tcp_wnd_end(tp))) return -EAGAIN; */
        int wnd_end = conn.sndUna() + conn.sndWnd();
        if (!TcpSequence.before(oldest.startSeq(), wnd_end)) {
            return;
        }

        oldest.markRetransmitted();                          // TCP_SKB_CB(skb)->sacked |= TCPCB_RETRANS
        oldest.updateSentTime(System.nanoTime() / 1_000L);  // skb_mstamp_ns update

        /* Re-use __tcp_transmit_skb: handles options, window selection, and tcp_event_ack_sent. */
        __tcp_transmit_skb(conn, oldest, conn.rcvNxt());
    }

    // ── tcp_write_xmit and tcp_send_fin ───────────────────────────────────

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
        boolean is_cwnd_limited = false;
        boolean is_rwnd_limited = false;

        tcp_mstamp_refresh(conn);

        if (push_one == 0) {
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
                        tcp_skb_is_last(conn, skb) ? nonagle : TCP_NAGLE_PUSH)) {
                    break;
                }
            } else {
                if (push_one == 0
                        && tcp_tso_should_defer(conn, skb, is_cwnd_limited, is_rwnd_limited, max_segs)) {
                    break;
                }
            }

            /* Skip empty non-FIN/SYN skbs (mirrors Linux TCP_SKB_CB(skb)->seq == end_seq check). */
            if (skb.dataLen() == 0 && !skb.isFin() && !skb.isSyn()) {
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
                skb = conn.tcpSendHead();
                if (skb == null) {
                    break;
                }
            }

            if (tcp_small_queue_check(conn, skb, push_one)) {
                break;
            }

            if (0 != tcp_transmit_skb(conn, skb)) {
                break;
            }

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
            if (push_one != 2) {
                // tcp_schedule_loss_probe(conn, false);
            }
            return false;
        }
        return conn.packetsOut() == 0 && conn.tcpSendHead() != null;
    }

    /**
     * Queue a FIN segment and push all pending data.
     *
     * <p>Mirrors Linux {@code tcp_send_fin}: enqueues a zero-payload SKB with
     * {@code TCPHDR_ACK | TCPHDR_FIN}, then calls {@code __tcp_push_pending_frames}
     * ({@link #tcp_write_xmit}) to transmit all pending segments including the FIN.
     *
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_output.c#L3442">tcp_send_fin</a>
     */
    public void tcp_send_fin(TcpConnection conn) {
        ByteBuf empty = conn.channel().alloc().buffer(0, 0);
        conn.tcp_queue_skb(new TcpSegmentEntry(
                empty, conn.writeSeq(), 0, (byte) (TCPHDR_ACK | TCPHDR_FIN), 0L));
        tcp_write_xmit(conn, conn.mss(), TCP_NAGLE_OFF, 0);
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Handshake sends  (no TcpConnection yet, raw addressing)
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Send a SYN-ACK during 3-way handshake.
     *
     * <p>Mirrors Linux {@code tcp_v4_send_synack} / {@code tcp_make_synack}.
     * Returns the {@link ChannelFuture} so the caller ({@code TcpHandshaker}) can attach
     * the retransmit-timer listener.
     *
     * <p><b>Address convention</b>: {@code localIp}/{@code localPort} are <em>our</em> addresses
     * (destination of the original SYN); {@code remoteIp}/{@code remotePort} are the client's.
     *
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_ipv4.c#L944">tcp_v4_send_synack</a>
     */
    public ChannelFuture tcp_send_synack(TcpSockChannel ch,
            byte[] localIp, int localPort, byte[] remoteIp, int remotePort,
            int seq, int ack, int window, byte[] options) {
        ByteBuf buf = TcpPacketBuilder.buildRaw(
                localIp, localPort, remoteIp, remotePort,
                seq, ack, TCPHDR_SYN | TCPHDR_ACK, window, options, null, 0);
        return ch.writeAndFlush(buf);
    }

    /**
     * Send a challenge ACK during handshake (out-of-window segment, RFC 9293 §3.5).
     *
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_input.c#L3649">tcp_send_challenge_ack</a>
     */
    public void tcp_send_challenge_ack_handshake(TcpSockChannel ch,
            byte[] localIp, int localPort, byte[] remoteIp, int remotePort,
            int seq, int ack, int window) {
        ByteBuf buf = TcpPacketBuilder.buildRaw(
                localIp, localPort, remoteIp, remotePort,
                seq, ack, TCPHDR_ACK, window, null, null, 0);
        ch.writeRaw(buf);
    }

    /**
     * Send a RST during handshake (e.g. colliding SYN or bad ACK number).
     *
     * <p>Returns the {@link ChannelFuture} so the caller can attach a close listener.
     *
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_output.c#L3615">tcp_send_reset</a>
     */
    public ChannelFuture tcp_send_reset_handshake(TcpSockChannel ch,
            byte[] localIp, int localPort, byte[] remoteIp, int remotePort,
            int seq) {
        ByteBuf buf = TcpPacketBuilder.buildRaw(
                localIp, localPort, remoteIp, remotePort,
                seq, 0, TCPHDR_RST, 0, null, null, 0);
        return ch.writeRaw(buf);
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Stateless RST  (listen path — no connection)
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Send a stateless RST in response to a packet that has no matching connection.
     *
     * <p>Mirrors Linux {@code tcp_v4_send_reset} (listen path): if the incoming segment
     * carries an ACK, use {@code SEG.ACK} as the RST sequence number; otherwise reply with
     * {@code RST+ACK} acknowledging the SYN/data.
     * Never responds to a RST with another RST (RFC 9293 §3.5.2).
     *
     * <p>Writes directly to the TUN {@link ChannelHandlerContext} — no connection channel exists.
     *
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_ipv4.c#L711">tcp_v4_send_reset</a>
     */
    public void tcp_v4_send_reset(ChannelHandlerContext ctx, TcpPacketBuf pkt) {
        if (pkt.isRst()) return;  // RFC 9293 §3.5.2: never RST a RST

        byte[] srcIp   = pkt.dstAddrBytes();
        int    srcPort = pkt.tcpDstPort();
        byte[] dstIp   = pkt.srcAddrBytes();
        int    dstPort = pkt.tcpSrcPort();

        int seq, ack, flags;
        if (pkt.isAck()) {
            seq   = pkt.tcpAckNum();
            ack   = 0;
            flags = TCPHDR_RST;
        } else {
            seq   = 0;
            ack   = pkt.tcpSeq() + pkt.tcpPayloadLength() + (pkt.isSyn() ? 1 : 0);
            flags = TCPHDR_RST | TCPHDR_ACK;
        }

        ByteBuf buf = TcpPacketBuilder.buildRaw(
                srcIp, srcPort, dstIp, dstPort,
                seq, ack, flags, 0, null, null, 0);
        ctx.writeAndFlush(buf);
    }

    // ═══════════════════════════════════════════════════════════════════════
    // tcp_write_xmit internals  (mirrors tcp_output.c sub-functions)
    // ═══════════════════════════════════════════════════════════════════════

    /** @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_output.c#L55">tcp_mstamp_refresh</a> */
    private void tcp_mstamp_refresh(TcpConnection conn) {
        // TODO: update tp->tcp_clock_cache / tp->tcp_mstamp
    }

    /**
     * MTU probing — not implemented.
     * @return -1 (skip probe); 0 = early-return false; >0 = probe sent
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_output.c#L2434">tcp_mtu_probe</a>
     */
    private int tcp_mtu_probe(TcpConnection conn) {
        return -1;
    }

    /**
     * Max TSO segments — TSO not implemented; returns {@link Integer#MAX_VALUE}.
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_output.c#L2040">tcp_tso_segs</a>
     */
    private int tcp_tso_segs(TcpConnection conn, int mss_now) {
        return Integer.MAX_VALUE;
    }

    /**
     * Pacing gate — pacing not implemented; always {@code false}.
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_output.c#L2769">tcp_pacing_check</a>
     */
    private boolean tcp_pacing_check(TcpConnection conn) {
        return false;
    }

    /**
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_output.c#L2303">tcp_cwnd_test</a>
     */
    private int tcp_cwnd_test(TcpConnection conn) {
        int cwnd      = conn.congestionControl().cwnd(conn);
        int in_flight = conn.sendBuffer().rtxQueueSize();
        return Math.max(0, cwnd - in_flight);
    }

    /** Always 1 — no TSO/GSO. @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_output.c#L1614">tcp_set_skb_tso_segs</a> */
    private int tcp_set_skb_tso_segs(TcpSegmentEntry skb, int mss_now) {
        return 1;
    }

    /** @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_output.c#L1985">tcp_snd_wnd_test</a> */
    private boolean tcp_snd_wnd_test(TcpConnection conn, TcpSegmentEntry skb, int mss_now) {
        int end_seq = skb.startSeq() + Math.min(skb.dataLen(), mss_now);
        int wnd_end = conn.sndUna() + conn.sndWnd();
        return !TcpSequence.after(end_seq, wnd_end);
    }

    /** @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_output.c#L1780">tcp_skb_is_last</a> */
    private boolean tcp_skb_is_last(TcpConnection conn, TcpSegmentEntry skb) {
        return conn.sendBuffer().writeQueueSize() == 1;
    }

    /** @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_output.c#L1785">tcp_nagle_test</a> */
    private boolean tcp_nagle_test(TcpConnection conn, TcpSegmentEntry skb, int mss_now, int nonagle) {
        if ((nonagle & (TCP_NAGLE_OFF | TCP_NAGLE_PUSH)) != 0) {
            return true;
        }
        return skb.dataLen() >= mss_now || conn.packetsOut() == 0;
    }

    /** TSO deferral — not implemented; always {@code false}. @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_output.c#L2065">tcp_tso_should_defer</a> */
    private boolean tcp_tso_should_defer(TcpConnection conn, TcpSegmentEntry skb,
                                         boolean is_cwnd_limited, boolean is_rwnd_limited,
                                         int max_segs) {
        return false;
    }

    /** Urgent-mode — not implemented; always {@code false}. @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_output.c#L1993">tcp_urg_mode</a> */
    private boolean tcp_urg_mode(TcpConnection conn) {
        return false;
    }

    /** Simplified: always {@code mss_now}. @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_output.c#L1741">tcp_mss_split_point</a> */
    private int tcp_mss_split_point(TcpConnection conn, TcpSegmentEntry skb, int mss_now,
                                    int cwnd_quota, int nonagle) {
        return mss_now;
    }

    /**
     * Split write-queue head at {@code limit} bytes.
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_output.c#L1559">tcp_fragment</a>
     */
    private boolean tcp_fragment(TcpConnection conn, TcpSegmentEntry skb, int limit, int mss_now) {
        ByteBuf origPayload = skb.payload();
        int     origReader  = origPayload.readerIndex();

        ByteBuf headBuf = origPayload.retainedSlice(origReader, limit);
        int     tailLen = skb.dataLen() - limit;
        ByteBuf tailBuf = origPayload.retainedSlice(origReader + limit, tailLen);

        byte headFlags = (byte) (skb.tcpFlags() & ~(TCPHDR_FIN | TCPHDR_PSH));
        byte tailFlags = skb.tcpFlags();

        TcpSegmentEntry headEntry = new TcpSegmentEntry(headBuf, skb.startSeq(),         limit,   headFlags, 0L);
        TcpSegmentEntry tailEntry = new TcpSegmentEntry(tailBuf, skb.startSeq() + limit, tailLen, tailFlags, 0L);

        conn.sendBuffer().pollWrite().release();
        conn.sendBuffer().enqueueWriteFirst(tailEntry);
        conn.sendBuffer().enqueueWriteFirst(headEntry);
        return false;
    }

    /** Not implemented; always {@code false}. @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_output.c#L2374">tcp_small_queue_check</a> */
    private boolean tcp_small_queue_check(TcpConnection conn, TcpSegmentEntry skb, int push_one) {
        return false;
    }

    /** @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_output.c#L1486">tcp_transmit_skb</a> */
    private int tcp_transmit_skb(TcpConnection conn, TcpSegmentEntry skb) {
        return __tcp_transmit_skb(conn, skb, conn.rcvNxt());
    }

    /**
     * Build and write one data segment.
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_output.c#L1290">__tcp_transmit_skb</a>
     */
    private int __tcp_transmit_skb(TcpConnection conn, TcpSegmentEntry skb, int rcv_nxt) {
        if (skb == null) return -1;

        final byte[]    rawOptions = tcp_established_options(conn, skb);
        final FourTuple ft         = fourTuple(conn);

        int tcpFlags = (skb.tcpFlags() & 0xFF) | TCPHDR_ACK;
        if (skb.dataLen() > 0) {
            tcpFlags |= TCPHDR_PSH;
        }

        ByteBuf buf = TcpPacketBuilder.buildRaw(
                ft.dstAddrBytes(), ft.dstPort(),
                ft.srcAddrBytes(), ft.srcPort(),
                skb.startSeq(), rcv_nxt,
                tcpFlags, selectAdvertisedWindow(conn),
                rawOptions, skb.payload(), skb.dataLen());

        writeRaw(conn, buf);

        // Piggy-backed ACK → mirrors tcp_event_ack_sent → inet_csk_clear_xmit_timer(DACK).
        tcp_event_ack_sent(conn, rcv_nxt);

        if (skb.dataLen() > 0) {
            tcp_event_data_sent(conn);
        }
        return 0;
    }

    /**
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_output.c#L182">tcp_event_ack_sent</a>
     */
    private void tcp_event_ack_sent(TcpConnection conn, int rcv_nxt) {
        if (rcv_nxt != conn.rcvNxt()) return;
        // TODO: tcp_dec_quickack_mode(conn)
        if (conn.hasAckPending(TcpConstants.ACK_SCHED | TcpConstants.ACK_TIMER)) {
            if (conn.hasAckPending(TcpConstants.ACK_TIMER)) {
                TcpTimerScheduler.INSTANCE.cancelDelayedAck(conn);
            }
            conn.clearAckPending(TcpConstants.ACK_SCHED | TcpConstants.ACK_TIMER);
        }
    }

    /**
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_output.c#L163">tcp_event_data_sent</a>
     */
    private void tcp_event_data_sent(TcpConnection conn) {
        // TODO: tp.lsndtime = tcp_jiffies32()
        // TODO: pingpong increment
    }

    /**
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_output.c#L633">tcp_established_options</a>
     */
    private byte[] tcp_established_options(TcpConnection conn, TcpSegmentEntry skb) {
        // TODO: TCP timestamps, SACK blocks
        return null;
    }

    /**
     * Advance SND.NXT, promote skb from write queue to RTX queue, arm RTO.
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_output.c#L2595">tcp_event_new_data_sent</a>
     */
    private void tcp_event_new_data_sent(TcpConnection conn, TcpSegmentEntry skb) {
        conn.sndNxt(skb.endSeq());

        conn.sendBuffer().pollWrite();
        final boolean startRto = !conn.sendBuffer().hasRtxPending();
        skb.updateSentTime(System.nanoTime() / 1_000L);
        conn.sendBuffer().enqueueRtx(skb);

        conn.incrementPacketsOut();

        if (startRto) {
            TcpRetransmitter.INSTANCE.scheduleRetransmit(conn);
        }
    }

    /** @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_output.c#L566">tcp_minshall_update</a> */
    private void tcp_minshall_update(TcpConnection conn, int mss_now, TcpSegmentEntry skb) {
        // TODO: snd_sml
    }

    /** Always 1 — no TSO/GSO. */
    private int tcp_skb_pcount(TcpSegmentEntry skb) {
        return 1;
    }

    /** @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_output.c#L2745">tcp_in_cwnd_reduction</a> */
    private boolean tcp_in_cwnd_reduction(TcpConnection conn) {
        return false;
    }

    /** @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_output.c#L2359">tcp_cwnd_validate</a> */
    private void tcp_cwnd_validate(boolean is_cwnd_limited) {
        // TODO: tp->is_cwnd_limited, tp->max_packets_out
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Shared helpers
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Select and advertise the receive window, updating {@code rcv_wup}.
     * Mirrors Linux {@code tcp_select_window}.
     */
    private static int selectAdvertisedWindow(TcpConnection conn) {
        conn.rcvWup(conn.rcvNxt());
        int wnd = conn.rcvWnd() >> conn.rcvWscale();
        return Math.min(Math.max(wnd, 0), 65535);
    }

    private static FourTuple fourTuple(TcpConnection conn) {
        return ((TcpSockChannel) conn.channel()).fourTuple();
    }

    /** Single write-path: all established-state sends go through here. */
    private static void writeRaw(TcpConnection conn, ByteBuf buf) {
        ((TcpSockChannel) conn.channel()).writeRaw(buf);
    }
}

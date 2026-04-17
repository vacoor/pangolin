package com.github.pangolin.routing.acceptor.tun.net.v2.tcp.core;

import com.github.pangolin.routing.acceptor.tun.net.handler.support.TcpPacketBuf;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.connection.TcpConnection;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.connection.TcpSendBuffer.TcpSegmentEntry;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.internal.FourTuple;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.internal.TcpConstants;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.internal.TcpSequence;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.ng.TcpMultiplexer.TcpSock;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.timer.TimerType;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.timer.TcpTimerScheduler;
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.buffer.Unpooled;

import static com.github.pangolin.routing.acceptor.tun.net.handler.tcp.util.TcpClock.tcp_clock_ms;
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
    public void tcp_send_ack(TcpSock sock) {
        if (sock == null || !sock.hasConnection()) {
            return;
        }
        final int rcv_nxt = sock.rcvNxt();
        FourTuple ft = sock.fourTuple();
        int wnd = selectAdvertisedWindow(sock);
        ByteBuf buf = TcpPacketBuilder.buildRaw(
                ft.dstAddrBytes(), ft.dstPort(),
                ft.srcAddrBytes(), ft.srcPort(),
                sock.sndNxt(), rcv_nxt,
                TCPHDR_ACK, wnd, null, null, 0);
        sock.channel().writeAndFlush(buf);
        tcp_event_ack_sent(sock, rcv_nxt);
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
    public void tcp_send_challenge_ack(TcpSock sock, boolean accecn_reflector) {
        if (sock == null || !sock.hasConnection()) {
            return;
        }
        long now = tcp_jiffies32();
        long lastOow = sock.lastOowAckTimeMs();
        if (lastOow != 0) {
            long elapsed = now - lastOow;
            if (elapsed >= 0 && elapsed < TcpConstants.INVALID_ACK_RATELIMIT_MS) {
                return;
            }
        }
        sock.lastOowAckTimeMs(now);
        tcp_send_ack(sock);
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
    public void tcp_send_reset(TcpSock sock) {
        if (sock == null || !sock.hasConnection()) {
            return;
        }
        FourTuple ft = sock.fourTuple();
        ByteBuf buf = TcpPacketBuilder.buildRaw(
                ft.dstAddrBytes(), ft.dstPort(),
                ft.srcAddrBytes(), ft.srcPort(),
                sock.sndNxt(), sock.rcvNxt(),
                TCPHDR_RST | TCPHDR_ACK, 0, null, null, 0);
        sock.channel().writeAndFlush(buf);
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
    public void tcp_retransmit_skb(TcpSock sock) {
        if (sock == null || !sock.hasConnection()) {
            return;
        }
        TcpSegmentEntry oldest = sock.sendBuffer().peekRtx();
        if (oldest == null) {
            return;
        }

        int wnd_end = sock.sndUna() + sock.sndWnd();
        if (!TcpSequence.before(oldest.startSeq(), wnd_end)) {
            return;
        }

        oldest.markRetransmitted();
        oldest.updateSentTime(System.nanoTime() / 1_000L);
        __tcp_transmit_skb(sock, oldest, sock.rcvNxt());
    }

    // ── tcp_write_xmit and tcp_send_fin ───────────────────────────────────

    /**
     * Drain the send buffer: segment and transmit pending data within the current
     * congestion window and peer receive window.
     *
     * @param sock     the socket whose send buffer to drain
     * @param mss_now  current MSS (≈ {@code tcp_current_mss()})
     * @param nonagle  Nagle flags ({@link TcpConstants#TCP_NAGLE_OFF} etc.)
     * @param push_one 0 = send as many as allowed; 1 = at most one; 2 = force loss probe
     * @return {@code true} if data is queued but the send window is closed and no segments
     *         are in flight — caller should arm the persist/probe timer;
     *         {@code false} otherwise
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_output.c#L2791">tcp_write_xmit</a>
     */
    public boolean tcp_write_xmit(TcpSock sock, int mss_now, int nonagle, int push_one) {
        if (sock == null || !sock.hasConnection()) {
            return false;
        }
        int sent_pkts = 0;
        boolean is_cwnd_limited = false;
        boolean is_rwnd_limited = false;

        tcp_mstamp_refresh(sock);

        if (push_one == 0) {
            int mtu = tcp_mtu_probe(sock);
            if (mtu == 0) {
                return false;
            } else if (mtu > 0) {
                sent_pkts = 1;
            }
        }

        final int max_segs = tcp_tso_segs(sock, mss_now);
        TcpSegmentEntry skb;
        while ((skb = sock.tcpSendHead()) != null) {
            if (tcp_pacing_check(sock)) {
                break;
            }

            int cwnd_quota = tcp_cwnd_test(sock);
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
            if (!tcp_snd_wnd_test(sock, skb, mss_now)) {
                is_rwnd_limited = true;
                break;
            }

            if (tso_segs == 1) {
                if (!tcp_nagle_test(sock, skb, mss_now,
                        tcp_skb_is_last(sock, skb) ? nonagle : TCP_NAGLE_PUSH)) {
                    break;
                }
            } else {
                if (push_one == 0
                        && tcp_tso_should_defer(sock, skb, is_cwnd_limited, is_rwnd_limited, max_segs)) {
                    break;
                }
            }

            if (skb.dataLen() == 0 && !skb.isFin() && !skb.isSyn()) {
                break;
            }

            int limit = mss_now;
            if (tso_segs > 1 && !tcp_urg_mode(sock)) {
                limit = tcp_mss_split_point(sock, skb, mss_now, cwnd_quota, nonagle);
            }

            if (skb.dataLen() > limit) {
                if (tcp_fragment(sock, skb, limit, mss_now)) {
                    break;
                }
                skb = sock.tcpSendHead();
                if (skb == null) {
                    break;
                }
            }

            if (tcp_small_queue_check(sock, skb, push_one)) {
                break;
            }

            if (0 != tcp_transmit_skb(sock, skb)) {
                break;
            }

            tcp_event_new_data_sent(sock, skb);
            tcp_minshall_update(sock, mss_now, skb);
            sent_pkts += tcp_skb_pcount(skb);

            if (push_one != 0) {
                break;
            }
        }

        is_cwnd_limited |= (sock.packetsOut() >= sock.cwnd());
        if (sent_pkts != 0 || is_cwnd_limited) {
            tcp_cwnd_validate(is_cwnd_limited);
        }
        if (sent_pkts != 0) {
            if (tcp_in_cwnd_reduction(sock)) {
            }
            if (push_one != 2) {
                tcp_schedule_loss_probe(sock);
            }
            return false;
        }
        return sock.packetsOut() == 0 && sock.tcpSendHead() != null;
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
    public void tcp_send_fin(TcpSock sock) {
        if (sock == null || !sock.hasConnection()) {
            return;
        }
        ByteBuf empty = sock.channel().alloc().buffer(0, 0);
        sock.tcp_queue_skb(new TcpSegmentEntry(
                empty, sock.writeSeq(), 0, (byte) (TCPHDR_ACK | TCPHDR_FIN), 0L));
        tcp_write_xmit(sock, sock.mss(), TCP_NAGLE_OFF, 0);
    }

    public int tcp_write_wakeup(TcpSock sock, int mib) {
        if (sock == null || !sock.hasConnection() || sock.state() == com.github.pangolin.routing.acceptor.tun.net.v2.tcp.connection.TcpConnectionState.TCP_CLOSED) {
            return -1;
        }

        TcpSegmentEntry skb = sock.tcpSendHead();
        int wndEnd = sock.sndUna() + sock.sndWnd();
        if (skb != null && TcpSequence.before(skb.startSeq(), wndEnd)) {
            int priorPackets = sock.packetsOut();
            long priorSndNxt = Integer.toUnsignedLong(sock.sndNxt());
            tcp_write_xmit(sock, tcp_current_mss(sock), TCP_NAGLE_OFF, 1);
            return (sock.packetsOut() != priorPackets || Integer.toUnsignedLong(sock.sndNxt()) != priorSndNxt) ? 0 : 0;
        }
        return tcp_xmit_probe_skb(sock, 0, mib);
    }

    public long tcp_send_probe0(TcpSock sock) {
        if (sock == null || !sock.hasConnection()) {
            return -1L;
        }
        int err = tcp_write_wakeup(sock, 0);

        if (sock.packetsOut() > 0 || sock.tcpSendHead() == null) {
            sock.resetProbeState();
            return -1L;
        }

        sock.probesOut(sock.probesOut() + 1);
        long timeout;
        if (err <= 0) {
            if (sock.probeBackoffShift() < TcpConstants.TCP_RETRIES2) {
                sock.incProbeBackoff();
            }
            timeout = sock.tcpProbe0WhenMs(TcpConstants.RTO_MAX_MS);
        } else {
            timeout = TcpConstants.TCP_RESOURCE_PROBE_INTERVAL_MS;
        }

        timeout = sock.tcpClampProbe0ToUserTimeout(timeout);
        return timeout;
    }

    public void tcp_send_loss_probe(TcpSock sock) {
        if (sock == null || !sock.hasConnection()) {
            return;
        }
        if (sock.tcpSendHead() != null) {
            tcp_write_xmit(sock, tcp_current_mss(sock), TCP_NAGLE_OFF, 2);
            return;
        }
        if (sock.sendBuffer() != null && sock.sendBuffer().peekRtx() != null) {
            tcp_retransmit_skb(sock);
        }
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
    public ChannelFuture tcp_send_synack(Channel ch,
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
    public void tcp_send_challenge_ack_handshake(Channel ch,
            byte[] localIp, int localPort, byte[] remoteIp, int remotePort,
            int seq, int ack, int window) {
        ByteBuf buf = TcpPacketBuilder.buildRaw(
                localIp, localPort, remoteIp, remotePort,
                seq, ack, TCPHDR_ACK, window, null, null, 0);
        ch.writeAndFlush(buf);
    }

    /**
     * Send a RST during handshake (e.g. colliding SYN or bad ACK number).
     *
     * <p>Returns the {@link ChannelFuture} so the caller can attach a close listener.
     *
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_output.c#L3615">tcp_send_reset</a>
     */
    public ChannelFuture tcp_send_reset_handshake(Channel ch,
            byte[] localIp, int localPort, byte[] remoteIp, int remotePort,
            int seq) {
        ByteBuf buf = TcpPacketBuilder.buildRaw(
                localIp, localPort, remoteIp, remotePort,
                seq, 0, TCPHDR_RST, 0, null, null, 0);
        return ch.writeAndFlush(buf);
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
        // v2 connection view does not keep a separate tcp_clock_cache/tcp_mstamp field.
    }

    private void tcp_mstamp_refresh(TcpSock sock) {
        // v2 does not cache tcp_clock_cache/tcp_mstamp separately; RTT uses per-segment stamps.
    }

    /**
     * MTU probing — not implemented.
     * @return -1 (skip probe); 0 = early-return false; >0 = probe sent
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_output.c#L2434">tcp_mtu_probe</a>
     */
    private int tcp_mtu_probe(TcpConnection conn) {
        return -1;
    }

    private int tcp_mtu_probe(TcpSock sock) {
        return -1;
    }

    /**
     * Current MSS estimate for the send path.
     * Mirrors the role of Linux {@code tcp_current_mss()} but keeps the existing
     * v2 simplification: use the negotiated/application MSS directly.
     */
    public int tcp_current_mss(TcpSock sock) {
        if (sock == null || sock.mss() <= 0) {
            return TcpConstants.TCP_MSS_DEFAULT;
        }
        return sock.mss();
    }

    /**
     * Max TSO segments — TSO not implemented; returns {@link Integer#MAX_VALUE}.
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_output.c#L2040">tcp_tso_segs</a>
     */
    private int tcp_tso_segs(TcpConnection conn, int mss_now) {
        return Integer.MAX_VALUE;
    }

    private int tcp_tso_segs(TcpSock sock, int mss_now) {
        return Integer.MAX_VALUE;
    }

    /**
     * Pacing gate — pacing not implemented; always {@code false}.
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_output.c#L2769">tcp_pacing_check</a>
     */
    private boolean tcp_pacing_check(TcpConnection conn) {
        return false;
    }

    private boolean tcp_pacing_check(TcpSock sock) {
        return false;
    }

    /**
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_output.c#L2303">tcp_cwnd_test</a>
     */
    private int tcp_cwnd_test(TcpSock sock) {
        int cwnd = sock.cwnd();
        int in_flight = sock.sendBuffer().rtxQueueSize();
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

    private boolean tcp_snd_wnd_test(TcpSock sock, TcpSegmentEntry skb, int mss_now) {
        int end_seq = skb.startSeq() + Math.min(skb.dataLen(), mss_now);
        int wnd_end = sock.sndUna() + sock.sndWnd();
        return !TcpSequence.after(end_seq, wnd_end);
    }

    /** @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_output.c#L1780">tcp_skb_is_last</a> */
    private boolean tcp_skb_is_last(TcpConnection conn, TcpSegmentEntry skb) {
        return conn.sendBuffer().writeQueueSize() == 1;
    }

    private boolean tcp_skb_is_last(TcpSock sock, TcpSegmentEntry skb) {
        return sock.sendBuffer().writeQueueSize() == 1;
    }

    /** @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_output.c#L1785">tcp_nagle_test</a> */
    private boolean tcp_nagle_test(TcpConnection conn, TcpSegmentEntry skb, int mss_now, int nonagle) {
        if ((nonagle & (TCP_NAGLE_OFF | TCP_NAGLE_PUSH)) != 0) {
            return true;
        }
        return skb.dataLen() >= mss_now || conn.packetsOut() == 0;
    }

    private boolean tcp_nagle_test(TcpSock sock, TcpSegmentEntry skb, int mss_now, int nonagle) {
        if ((nonagle & (TCP_NAGLE_OFF | TCP_NAGLE_PUSH)) != 0) {
            return true;
        }
        return skb.dataLen() >= mss_now || sock.packetsOut() == 0;
    }

    /** TSO deferral — not implemented; always {@code false}. @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_output.c#L2065">tcp_tso_should_defer</a> */
    private boolean tcp_tso_should_defer(TcpConnection conn, TcpSegmentEntry skb,
                                         boolean is_cwnd_limited, boolean is_rwnd_limited,
                                         int max_segs) {
        return false;
    }

    private boolean tcp_tso_should_defer(TcpSock sock, TcpSegmentEntry skb,
                                         boolean is_cwnd_limited, boolean is_rwnd_limited,
                                         int max_segs) {
        return false;
    }

    /** Urgent-mode — not implemented; always {@code false}. @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_output.c#L1993">tcp_urg_mode</a> */
    private boolean tcp_urg_mode(TcpConnection conn) {
        return false;
    }

    private boolean tcp_urg_mode(TcpSock sock) {
        return false;
    }

    /** Simplified: always {@code mss_now}. @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_output.c#L1741">tcp_mss_split_point</a> */
    private int tcp_mss_split_point(TcpConnection conn, TcpSegmentEntry skb, int mss_now,
                                    int cwnd_quota, int nonagle) {
        return mss_now;
    }

    private int tcp_mss_split_point(TcpSock sock, TcpSegmentEntry skb, int mss_now,
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

    private boolean tcp_fragment(TcpSock sock, TcpSegmentEntry skb, int limit, int mss_now) {
        ByteBuf origPayload = skb.payload();
        int origReader = origPayload.readerIndex();

        ByteBuf headBuf = origPayload.retainedSlice(origReader, limit);
        int tailLen = skb.dataLen() - limit;
        ByteBuf tailBuf = origPayload.retainedSlice(origReader + limit, tailLen);

        byte headFlags = (byte) (skb.tcpFlags() & ~(TCPHDR_FIN | TCPHDR_PSH));
        byte tailFlags = skb.tcpFlags();

        TcpSegmentEntry headEntry = new TcpSegmentEntry(headBuf, skb.startSeq(), limit, headFlags, 0L);
        TcpSegmentEntry tailEntry = new TcpSegmentEntry(tailBuf, skb.startSeq() + limit, tailLen, tailFlags, 0L);

        sock.sendBuffer().pollWrite().release();
        sock.sendBuffer().enqueueWriteFirst(tailEntry);
        sock.sendBuffer().enqueueWriteFirst(headEntry);
        return false;
    }

    /** Not implemented; always {@code false}. @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_output.c#L2374">tcp_small_queue_check</a> */
    private boolean tcp_small_queue_check(TcpConnection conn, TcpSegmentEntry skb, int push_one) {
        return false;
    }

    private boolean tcp_small_queue_check(TcpSock sock, TcpSegmentEntry skb, int push_one) {
        return false;
    }

    /** @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_output.c#L1486">tcp_transmit_skb</a> */
    private int tcp_transmit_skb(TcpConnection conn, TcpSegmentEntry skb) {
        return __tcp_transmit_skb(conn, skb, conn.rcvNxt());
    }

    private int tcp_transmit_skb(TcpSock sock, TcpSegmentEntry skb) {
        return __tcp_transmit_skb(sock, skb, sock.rcvNxt());
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

    private int __tcp_transmit_skb(TcpSock sock, TcpSegmentEntry skb, int rcv_nxt) {
        if (skb == null) {
            return -1;
        }

        final byte[] rawOptions = tcp_established_options(sock, skb);
        final FourTuple ft = sock.fourTuple();

        int tcpFlags = (skb.tcpFlags() & 0xFF) | TCPHDR_ACK;
        if (skb.dataLen() > 0) {
            tcpFlags |= TCPHDR_PSH;
        }

        ByteBuf buf = TcpPacketBuilder.buildRaw(
                ft.dstAddrBytes(), ft.dstPort(),
                ft.srcAddrBytes(), ft.srcPort(),
                skb.startSeq(), rcv_nxt,
                tcpFlags, selectAdvertisedWindow(sock),
                rawOptions, skb.payload(), skb.dataLen());

        sock.channel().writeAndFlush(buf);
        tcp_event_ack_sent(sock, rcv_nxt);

        if (skb.dataLen() > 0) {
            tcp_event_data_sent(sock);
        }
        return 0;
    }

    /**
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_output.c#L182">tcp_event_ack_sent</a>
     */
    private void tcp_event_ack_sent(TcpConnection conn, int rcv_nxt) {
        if (rcv_nxt != conn.rcvNxt()) {
            return;
        }
        conn.decQuickAckMode();
        if (conn.hasAckPending(TcpConstants.ACK_SCHED | TcpConstants.ACK_TIMER)) {
            if (conn.hasAckPending(TcpConstants.ACK_TIMER)) {
                TcpTimerScheduler.INSTANCE.cancelDelayedAck(conn);
            }
            conn.clearAckPending(TcpConstants.ACK_SCHED | TcpConstants.ACK_TIMER);
        }
        conn.clearAckPending(TcpConstants.ACK_NOW);
    }

    /**
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_output.c#L163">tcp_event_data_sent</a>
     */
    private void tcp_event_data_sent(TcpConnection conn) {
        conn.onDataSent();
    }

    private void tcp_event_data_sent(TcpSock sock) {
        sock.onDataSent();
    }

    /**
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_output.c#L633">tcp_established_options</a>
     */
    private byte[] tcp_established_options(TcpConnection conn, TcpSegmentEntry skb) {
        if (!conn.timestampEnabled()) {
            return null;
        }
        ByteBuf optBuf = Unpooled.buffer(12);
        try {
            long tsval = tcp_clock_ms() & 0xFFFFFFFFL;
            long tsecr = ((long) conn.recentTimestamp()) & 0xFFFFFFFFL;
            com.github.pangolin.routing.acceptor.tun.net.handler.tcp.util.TcpOptionCodec
                    .writeTimestampOption(optBuf, tsval, tsecr);
            byte[] bytes = new byte[optBuf.readableBytes()];
            optBuf.readBytes(bytes);
            return bytes;
        } finally {
            optBuf.release();
        }
    }

    private byte[] tcp_established_options(TcpSock sock, TcpSegmentEntry skb) {
        if (!sock.timestampEnabled()) {
            return null;
        }
        ByteBuf optBuf = Unpooled.buffer(12);
        try {
            long tsval = tcp_clock_ms() & 0xFFFFFFFFL;
            long tsecr = ((long) sock.recentTimestamp()) & 0xFFFFFFFFL;
            com.github.pangolin.routing.acceptor.tun.net.handler.tcp.util.TcpOptionCodec
                    .writeTimestampOption(optBuf, tsval, tsecr);
            byte[] bytes = new byte[optBuf.readableBytes()];
            optBuf.readBytes(bytes);
            return bytes;
        } finally {
            optBuf.release();
        }
    }

    /**
     * Advance SND.NXT, promote skb from write queue to RTX queue, arm RTO.
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_output.c#L2595">tcp_event_new_data_sent</a>
     */
    private void tcp_event_new_data_sent(TcpSock sock, TcpSegmentEntry skb) {
        sock.sndNxt(skb.endSeq());

        sock.sendBuffer().pollWrite();
        final boolean startRto = !sock.sendBuffer().hasRtxPending();
        skb.updateSentTime(System.nanoTime() / 1_000L);
        sock.sendBuffer().enqueueRtx(skb);

        sock.incrementPacketsOut();

        if (startRto || (sock.timers() != null && sock.timers().writeTimerType == TimerType.TLP_PROBE)) {
            TcpRetransmitter.INSTANCE.scheduleRetransmit(sock);
        }
    }

    private void tcp_schedule_loss_probe(TcpSock sock) {
        if (sock == null || !sock.hasConnection() || sock.packetsOut() == 0 || sock.sndWnd() == 0) {
            return;
        }
        if (sock.tlpHighSeq() != 0) {
            return;
        }
        if (sock.packetsOut() > 2) {
            return;
        }
        if (sock.timers() != null && sock.timers().writeTimerType == TimerType.ZERO_WINDOW_PROBE) {
            return;
        }
        long timeout = tcp_tlp_timeout(sock);
        if (timeout <= 0L || timeout >= sock.rtoMs()) {
            return;
        }
        sock.tlpHighSeq(sock.sndNxt());
        TcpRetransmitter.INSTANCE.scheduleLossProbe(sock, timeout);
    }

    private long tcp_tlp_timeout(TcpSock sock) {
        long srttMs = sock.srttUs() > 0L
                ? Math.max(sock.srttUs() / 1_000L, 1L)
                : Math.max(sock.rtoMs() >> 1, 1L);
        long timeout = (srttMs << 1) + Math.max(sock.ackTimeoutMs(), TcpConstants.TCP_ATO_MIN_MS);
        long rto = sock.rtoMs();
        if (rto <= 1L) {
            return 1L;
        }
        return Math.max(1L, Math.min(timeout, rto - 1L));
    }

    /** @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_output.c#L566">tcp_minshall_update</a> */
    private void tcp_minshall_update(TcpConnection conn, int mss_now, TcpSegmentEntry skb) {
        if (skb.dataLen() < tcp_skb_pcount(skb) * mss_now) {
            conn.sndSml(skb.endSeq());
        }
    }

    private void tcp_minshall_update(TcpSock sock, int mss_now, TcpSegmentEntry skb) {
        if (skb.dataLen() < tcp_skb_pcount(skb) * mss_now) {
            sock.sndSml(skb.endSeq());
        }
    }

    /** Always 1 — no TSO/GSO. */
    private int tcp_skb_pcount(TcpSegmentEntry skb) {
        return 1;
    }

    /** @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_output.c#L2745">tcp_in_cwnd_reduction</a> */
    private boolean tcp_in_cwnd_reduction(TcpConnection conn) {
        return false;
    }

    private boolean tcp_in_cwnd_reduction(TcpSock sock) {
        return false;
    }

    /** @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_output.c#L2359">tcp_cwnd_validate</a> */
    private void tcp_cwnd_validate(boolean is_cwnd_limited) {
        // v2 does not persist is_cwnd_limited/max_packets_out yet.
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

    private static int selectAdvertisedWindow(TcpSock sock) {
        sock.rcvWup(sock.rcvNxt());
        int wnd = sock.rcvWnd() >> sock.rcvWscale();
        return Math.min(Math.max(wnd, 0), 65535);
    }

    private static FourTuple fourTuple(TcpConnection conn) {
        return conn.fourTuple();
    }

    /** Single write-path: all established-state sends go through here. */
    private static void writeRaw(TcpConnection conn, ByteBuf buf) {
        conn.channel().writeAndFlush(buf);
    }

    private int tcp_xmit_probe_skb(TcpSock sock, int urgent, int mib) {
        final FourTuple ft = sock.fourTuple();
        int seq = sock.sndUna() - (urgent == 0 ? 1 : 0);
        ByteBuf buf = TcpPacketBuilder.buildRaw(
                ft.dstAddrBytes(), ft.dstPort(),
                ft.srcAddrBytes(), ft.srcPort(),
                seq, sock.rcvNxt(),
                TCPHDR_ACK, selectAdvertisedWindow(sock), null, null, 0);
        sock.channel().writeAndFlush(buf);
        tcp_event_ack_sent(sock, sock.rcvNxt());
        return 0;
    }

    private void tcp_event_ack_sent(TcpSock sock, int rcv_nxt) {
        if (rcv_nxt != sock.rcvNxt()) {
            return;
        }
        sock.decQuickAckMode();
        if (sock.hasAckPending(TcpConstants.ACK_SCHED | TcpConstants.ACK_TIMER)) {
            if (sock.hasAckPending(TcpConstants.ACK_TIMER)) {
                TcpTimerScheduler.INSTANCE.cancelDelayedAck(sock);
            }
            sock.clearAckPending(TcpConstants.ACK_SCHED | TcpConstants.ACK_TIMER);
        }
        sock.clearAckPending(TcpConstants.ACK_NOW);
    }
}

package com.github.pangolin.routing.acceptor.tun.net.v2.tcp.core;

import com.github.pangolin.routing.acceptor.tun.net.handler.support.TcpPacketBuf;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.connection.TcpConnection;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.connection.TcpSkb;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.internal.FourTuple;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.internal.SysctlOptions;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.internal.TcpConstants;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.internal.TcpSequence;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.internal.TcpTimewaitSock;
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
        TcpMibStats.INSTANCE.inc(TcpMib.TCPCHALLENGEACK);
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
     * (tcp_output.c:3448 / 3321):
     * <ol>
     *   <li><b>Stale trim</b> — mirrors {@code before(end_seq, snd_una) ⇒ -EINVAL}:
     *       when the entire segment has already been acknowledged, drop it silently
     *       (the RTX queue entry is kept for the caller to reclaim).</li>
     *   <li><b>Send-window guard</b> — mirrors {@code avail_wnd = tcp_wnd_end - seq;
     *       if (avail_wnd &lt;= 0 &amp;&amp; seq != snd_una) return -EAGAIN}: if the peer
     *       has shrunk its window below this segment, do <em>not</em> retransmit —
     *       <b>except</b> when the segment sits right at {@code SND.UNA}, in which
     *       case a single-segment retransmit serves as a zero-window probe.</li>
     *   <li><b>Mark retransmitted</b> — sets the Karn flag so RTT samples are suppressed
     *       for this segment ({@code TCPCB_RETRANS} / {@code TCPCB_EVER_RETRANS}).</li>
     *   <li><b>Stamp sent-time</b> — updates {@code skb->skb_mstamp} for future RTT
     *       measurement.</li>
     *   <li><b>Transmit</b> — via {@link #__tcp_transmit_skb}, which calls
     *       {@link #tcp_event_ack_sent} to piggy-back the ACK and clear delayed-ACK state.</li>
     * </ol>
     *
     * <p>Not implemented (in v2 architecture this branch never triggers):
     * <ul>
     *   <li><b>SYN fix-up</b> ({@code before(seq, snd_una) &amp;&amp; skb->syn}) — in v2 the SYN
     *       segment is owned by {@code TcpHandshaker} and never lands on the data RTX queue,
     *       so this branch cannot trigger here.</li>
     * </ul>
     *
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_output.c#L3321">__tcp_retransmit_skb</a>
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_output.c#L3448">tcp_retransmit_skb</a>
     */
    public void tcp_retransmit_skb(TcpSock sock) {
        if (sock == null || !sock.hasConnection()) {
            return;
        }
        // (0) 选段:若 RACK/FACK 已标记 LOST,优先重传 LOST 段;否则退回到 RTX 头段
        //     对齐 Linux {@code tcp_xmit_retransmit_queue} 的 LOST 驱动 + 首段兜底。
        TcpSkb oldest = null;
        if (sock.lostOut() > 0) {
            for (TcpSkb skb : sock.sendBuffer().rtxView()) {
                if (skb.isSackAcked()) continue;
                if (skb.isLost()) {
                    oldest = skb;
                    break;
                }
            }
        }
        if (oldest == null) {
            oldest = sock.sendBuffer().peekRtx();
        }
        if (oldest == null) {
            return;
        }

        // (1) Stale trim: entire segment has already been acknowledged.
        if (!TcpSequence.after(oldest.endSeq(), sock.sndUna())) {
            return;
        }

        // (2) Send-window guard with zero-window probe exception.
        final int wndEnd  = sock.sndUna() + sock.sndWnd();
        final int availWnd = wndEnd - oldest.startSeq();
        if (availWnd <= 0 && oldest.startSeq() != sock.sndUna()) {
            return;
        }

        // (3) tcp_fragment on RTX queue when skb->len > avail_wnd (Linux __tcp_retransmit_skb):
        //     对端窗口已收缩到段以下时,在 avail_wnd 边界切分,仅重传头段。
        //     头段对齐 MSS;若 avail_wnd 不足 1 MSS 但段仍在 SND.UNA,保留整段作为零窗探测。
        if (availWnd > 0 && oldest.dataLen() > availWnd) {
            final int mss = Math.max(sock.mss(), 1);
            int limit = (availWnd >= mss) ? (availWnd / mss) * mss : availWnd;
            if (limit > 0 && limit < oldest.dataLen()) {
                if (tcp_rtx_fragment(sock, oldest, limit)) {
                    oldest = sock.sendBuffer().peekRtx();
                    if (oldest == null) {
                        return;
                    }
                }
            }
        }

        // (4) retrans_stamp / undo_retrans 统计:首次进入重传路径打时戳,
        //     每个段首次被标记为 retransmitted 时 undo_retrans++(对齐 Linux
        //     __tcp_retransmit_skb: if (!tp->retrans_stamp) tp->retrans_stamp = ...;
        //     if (!err) tp->undo_retrans += pcount;)。
        final long nowUs = System.nanoTime() / 1_000L;
        if (sock.retransStamp() == 0L) {
            sock.retransStamp(nowUs);
        }
        if (!oldest.isRetransmitted()) {
            sock.incrUndoRetrans();
        }

        oldest.markRetransmitted();
        oldest.updateSentTime(nowUs);
        TcpMibStats.INSTANCE.inc(TcpMib.TCPRETRANSSEGS);
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
        TcpSkb skb;
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
        sock.tcp_queue_skb(new TcpSkb(
                empty, sock.writeSeq(), 0, (byte) (TCPHDR_ACK | TCPHDR_FIN), 0L));
        tcp_write_xmit(sock, sock.mss(), TCP_NAGLE_OFF, 0);
    }

    public int tcp_write_wakeup(TcpSock sock, int mib) {
        if (sock == null || !sock.hasConnection() || sock.state() == com.github.pangolin.routing.acceptor.tun.net.v2.tcp.connection.TcpConnectionState.TCP_CLOSED) {
            return -1;
        }

        TcpSkb skb = sock.tcpSendHead();
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
    /**
     * 对齐 Linux {@code tcp_v4_timewait_ack}(net/ipv4/tcp_ipv4.c):对 TIME_WAIT bucket 中
     * 迟到 FIN / 数据段重放 ACK。无关联 {@link TcpSock} / {@link TcpConnection},全部字段
     * 来自 {@link TcpTimewaitSock} 快照。
     *
     * <p>出口:优先使用 twsk 保留的 TUN 侧 channel;channel 若已失效,回退到调用点
     * 提供的 {@link ChannelHandlerContext}。
     */
    public void tcp_timewait_send_ack(ChannelHandlerContext ctx, TcpTimewaitSock tw) {
        if (tw == null) {
            return;
        }
        FourTuple ft = tw.fourTuple();
        ByteBuf buf = TcpPacketBuilder.buildRaw(
                ft.dstAddrBytes(), ft.dstPort(),
                ft.srcAddrBytes(), ft.srcPort(),
                tw.tw_snd_nxt, tw.tw_rcv_nxt,
                TCPHDR_ACK, tw.tw_rcv_wnd, null, null, 0);
        Channel ch = tw.tw_channel;
        if (ch != null && ch.isActive()) {
            ch.writeAndFlush(buf);
        } else if (ctx != null) {
            ctx.writeAndFlush(buf);
        } else {
            buf.release();
        }
    }

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
     * Current effective MSS for the send path.
     *
     * <p>Mirrors Linux {@code tcp_current_mss} (tcp_output.c:1865):
     * <ol>
     *   <li>以 {@code sock.mssCache()}(对齐 Linux {@code tp->mss_cache})为起点;
     *       {@code mssCache == 0} 时退化为 {@code sock.mss()}。</li>
     *   <li>若 {@code sock.dstMtu() > 0 && sock.dstMtu() != sock.pmtuCookie()},
     *       触发 {@link #tcp_sync_mss} 以新 PMTU 重新推导 {@code mssCache},对应
     *       Linux 的 <code>if (mtu != icsk->icsk_pmtu_cookie) mss_now = tcp_sync_mss(...)</code>。</li>
     *   <li>计算 {@code header_len = TCP_HDR + established_options_len};若与
     *       {@code sock.tcpHeaderLen()} 存在 delta,从 MSS 中扣减,对应
     *       Linux 的 <code>mss_now -= (header_len - tp->tcp_header_len)</code>。</li>
     * </ol>
     *
     * <p>v2 目前仅 TSopt 一种已协商的 ESTABLISHED 选项(SACK 块按段动态追加尚未启用),
     * 因此运行期 delta 在不启用 SACK 的路径下恒为 0。{@link #tcp_established_options_len}
     * 作为单一入口,后续引入 SACK 块后只需更新此处。
     *
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_output.c#L1865">tcp_current_mss</a>
     */
    public int tcp_current_mss(TcpSock sock) {
        if (sock == null) {
            return TcpConstants.TCP_MSS_DEFAULT;
        }
        int mss_now = sock.mssCache() > 0 ? sock.mssCache()
                : (sock.mss() > 0 ? sock.mss() : TcpConstants.TCP_MSS_DEFAULT);

        // PMTU 变化检测:dst_mtu 与缓存 cookie 不一致时重新同步
        int mtu = sock.dstMtu();
        if (mtu > 0 && mtu != sock.pmtuCookie()) {
            mss_now = tcp_sync_mss(sock, mtu);
        }

        // Option-length delta:握手期定型 tcp_header_len,运行期选项长度变化从 MSS 扣减
        int header_len = TcpConstants.TCP_MIN_HEADER_LEN + tcp_established_options_len(sock);
        int baseHdr = sock.tcpHeaderLen();
        if (baseHdr > 0 && header_len != baseHdr) {
            mss_now -= (header_len - baseHdr);
        } else if (baseHdr == 0 && sock.timestampEnabled()) {
            // 尚未初始化 tcpHeaderLen 的回退路径(向下兼容早期构造):按 TSopt 固定 12 字节扣减
            mss_now -= TCP_TSOPT_WIRE_LEN;
        }
        return Math.max(mss_now, TcpConstants.TCP_MIN_MSS);
    }

    /** @see TcpConstants#TCP_TSOPT_WIRE_LEN */
    private static final int TCP_TSOPT_WIRE_LEN = TcpConstants.TCP_TSOPT_WIRE_LEN;

    /**
     * 当前 ESTABLISHED 段内已协商选项的字节数(不含 TCP 固定 20 字节头)。
     * v2 当前只有 TSopt(已协商时占 12 字节);SACK tagging 状态机启用后将在此处追加 SACK 块长度。
     * 对应 Linux {@code tcp_established_options} 返回 size 的那部分。
     */
    private int tcp_established_options_len(TcpSock sock) {
        return sock != null && sock.timestampEnabled() ? TCP_TSOPT_WIRE_LEN : 0;
    }

    /**
     * Synchronize {@code mss_cache} to the current PMTU / ext-header constraints.
     *
     * <p>Mirrors Linux {@code tcp_sync_mss} (tcp_output.c:1840):
     * <ol>
     *   <li>Compute PMTU-derived MSS = {@code pmtu - iphdr - tcphdr}.</li>
     *   <li>Apply {@code tcp_bound_to_half_wnd} (see below) so a single segment cannot
     *       exceed half the maximum advertised window — matches Linux tcp_output.c:1717.</li>
     *   <li>Clamp at {@link TcpConstants#TCP_MIN_MSS}.</li>
     *   <li>当 {@code pmtu > 0} 时写入 {@code sock.pmtuCookie()};无论 pmtu 是否传入
     *       都同步 {@code sock.mssCache()} / {@code sock.mss()} / {@code sock.rcvMss()}。</li>
     * </ol>
     *
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_output.c#L1840">tcp_sync_mss</a>
     */
    public int tcp_sync_mss(TcpSock sock, int pmtu) {
        if (sock == null) {
            return TcpConstants.TCP_MSS_DEFAULT;
        }
        int mss = sock.mss() > 0 ? sock.mss() : TcpConstants.TCP_MSS_DEFAULT;
        if (pmtu > 0) {
            int pmtuMss = pmtu - TcpConstants.IP4_HEADER_LEN - TcpConstants.TCP_MIN_HEADER_LEN;
            if (pmtuMss > 0) {
                mss = Math.min(mss, pmtuMss);
            }
        }
        mss = tcp_bound_to_half_wnd(sock, mss);
        if (mss < TcpConstants.TCP_MIN_MSS) {
            mss = TcpConstants.TCP_MIN_MSS;
        }
        sock.mss(mss);
        sock.mssCache(mss);
        if (pmtu > 0) {
            sock.pmtuCookie(pmtu);
        }
        if (sock.rcvMss() <= 0 || sock.rcvMss() > mss) {
            sock.rcvMss(mss);
        }
        return mss;
    }

    /**
     * Ensure an MSS value does not exceed half the maximum advertised window.
     * Mirrors Linux {@code tcp_bound_to_half_wnd} (tcp_output.c:1717); without it a
     * single segment could fill the entire window, leaving no room for piggy-backed
     * ACKs.  v2 uses {@link TcpConstants#TCP_MAX_WINDOW} as the upper-bound reference
     * because per-peer {@code max_window} is not currently tracked.
     */
    private static int tcp_bound_to_half_wnd(TcpSock sock, int mss) {
        int halfWnd = TcpConstants.TCP_MAX_WINDOW >> 1;
        if (sock.sndWnd() > 0) {
            halfWnd = Math.max(halfWnd, sock.sndWnd() >> 1);
        }
        if (mss > halfWnd) {
            return Math.max(halfWnd, TcpConstants.TCP_MIN_MSS);
        }
        return mss;
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
    private int tcp_set_skb_tso_segs(TcpSkb skb, int mss_now) {
        return 1;
    }

    /** @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_output.c#L1985">tcp_snd_wnd_test</a> */
    private boolean tcp_snd_wnd_test(TcpConnection conn, TcpSkb skb, int mss_now) {
        int end_seq = skb.startSeq() + Math.min(skb.dataLen(), mss_now);
        int wnd_end = conn.sndUna() + conn.sndWnd();
        return !TcpSequence.after(end_seq, wnd_end);
    }

    private boolean tcp_snd_wnd_test(TcpSock sock, TcpSkb skb, int mss_now) {
        int end_seq = skb.startSeq() + Math.min(skb.dataLen(), mss_now);
        int wnd_end = sock.sndUna() + sock.sndWnd();
        return !TcpSequence.after(end_seq, wnd_end);
    }

    /** @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_output.c#L1780">tcp_skb_is_last</a> */
    private boolean tcp_skb_is_last(TcpConnection conn, TcpSkb skb) {
        return conn.sendBuffer().writeQueueSize() == 1;
    }

    private boolean tcp_skb_is_last(TcpSock sock, TcpSkb skb) {
        return sock.sendBuffer().writeQueueSize() == 1;
    }

    /** @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_output.c#L1785">tcp_nagle_test</a> */
    private boolean tcp_nagle_test(TcpConnection conn, TcpSkb skb, int mss_now, int nonagle) {
        if ((nonagle & (TCP_NAGLE_OFF | TCP_NAGLE_PUSH)) != 0) {
            return true;
        }
        return skb.dataLen() >= mss_now || conn.packetsOut() == 0;
    }

    private boolean tcp_nagle_test(TcpSock sock, TcpSkb skb, int mss_now, int nonagle) {
        if ((nonagle & (TCP_NAGLE_OFF | TCP_NAGLE_PUSH)) != 0) {
            return true;
        }
        return skb.dataLen() >= mss_now || sock.packetsOut() == 0;
    }

    /** TSO deferral — not implemented; always {@code false}. @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_output.c#L2065">tcp_tso_should_defer</a> */
    private boolean tcp_tso_should_defer(TcpConnection conn, TcpSkb skb,
                                         boolean is_cwnd_limited, boolean is_rwnd_limited,
                                         int max_segs) {
        return false;
    }

    private boolean tcp_tso_should_defer(TcpSock sock, TcpSkb skb,
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
    private int tcp_mss_split_point(TcpConnection conn, TcpSkb skb, int mss_now,
                                    int cwnd_quota, int nonagle) {
        return mss_now;
    }

    private int tcp_mss_split_point(TcpSock sock, TcpSkb skb, int mss_now,
                                    int cwnd_quota, int nonagle) {
        return mss_now;
    }

    /**
     * Split write-queue head at {@code limit} bytes.
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_output.c#L1559">tcp_fragment</a>
     */
    private boolean tcp_fragment(TcpConnection conn, TcpSkb skb, int limit, int mss_now) {
        ByteBuf origPayload = skb.payload();
        int     origReader  = origPayload.readerIndex();

        ByteBuf headBuf = origPayload.retainedSlice(origReader, limit);
        int     tailLen = skb.dataLen() - limit;
        ByteBuf tailBuf = origPayload.retainedSlice(origReader + limit, tailLen);

        byte headFlags = (byte) (skb.tcpFlags() & ~(TCPHDR_FIN | TCPHDR_PSH));
        byte tailFlags = skb.tcpFlags();

        TcpSkb headEntry = new TcpSkb(headBuf, skb.startSeq(),         limit,   headFlags, 0L);
        TcpSkb tailEntry = new TcpSkb(tailBuf, skb.startSeq() + limit, tailLen, tailFlags, 0L);

        conn.sendBuffer().pollWrite().release();
        conn.sendBuffer().enqueueWriteFirst(tailEntry);
        conn.sendBuffer().enqueueWriteFirst(headEntry);
        return false;
    }

    private boolean tcp_fragment(TcpSock sock, TcpSkb skb, int limit, int mss_now) {
        ByteBuf origPayload = skb.payload();
        int origReader = origPayload.readerIndex();

        ByteBuf headBuf = origPayload.retainedSlice(origReader, limit);
        int tailLen = skb.dataLen() - limit;
        ByteBuf tailBuf = origPayload.retainedSlice(origReader + limit, tailLen);

        byte headFlags = (byte) (skb.tcpFlags() & ~(TCPHDR_FIN | TCPHDR_PSH));
        byte tailFlags = skb.tcpFlags();

        TcpSkb headEntry = new TcpSkb(headBuf, skb.startSeq(), limit, headFlags, 0L);
        TcpSkb tailEntry = new TcpSkb(tailBuf, skb.startSeq() + limit, tailLen, tailFlags, 0L);

        sock.sendBuffer().pollWrite().release();
        sock.sendBuffer().enqueueWriteFirst(tailEntry);
        sock.sendBuffer().enqueueWriteFirst(headEntry);
        return false;
    }

    /**
     * 就地切分 RTX 队列头段 — 对应 Linux {@code tcp_fragment(sk, TCP_FRAG_IN_RTX_QUEUE, skb, limit, mss, GFP_ATOMIC)}.
     *
     * <p>用途:`__tcp_retransmit_skb` 在对端窗口已收缩到 {@code skb->len} 以下时,需要把 RTX
     * 队列头段切成 head(适配 {@code avail_wnd})+ tail(余量) 两段,仅重传 head。
     *
     * <p>实现要点:
     * <ul>
     *   <li>从 RTX 队列弹出原头段,释放旧 payload(新 head / tail 使用 {@code retainedSlice});</li>
     *   <li>头段清掉 {@code FIN / PSH} 位(不能随中间段出现),尾段保留原 flags;</li>
     *   <li>{@code sacked}(含 {@code TCPCB_SACKED_RETRANS / EVER_RETRANS / LOST} 等)
     *       由 head / tail 各自继承,保证 sacktag 状态不被切分破坏;</li>
     *   <li>{@code sentTimeUs} 同样继承(重传调用者会在随后 {@link TcpSkb#updateSentTime}
     *       更新 head 的时间戳);</li>
     *   <li>切分后的两段按 head → tail 顺序插回队首,维持 seq 升序。</li>
     * </ul>
     *
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_output.c#L1559">tcp_fragment</a>
     */
    private boolean tcp_rtx_fragment(TcpSock sock, TcpSkb skb, int limit) {
        if (limit <= 0 || limit >= skb.dataLen()) {
            return false;
        }
        ByteBuf origPayload = skb.payload();
        int origReader = origPayload.readerIndex();

        ByteBuf headBuf = origPayload.retainedSlice(origReader, limit);
        int tailLen = skb.dataLen() - limit;
        ByteBuf tailBuf = origPayload.retainedSlice(origReader + limit, tailLen);

        byte headFlags = (byte) (skb.tcpFlags() & ~(TCPHDR_FIN | TCPHDR_PSH));
        byte tailFlags = skb.tcpFlags();

        TcpSkb headEntry = new TcpSkb(
                headBuf, skb.startSeq(), limit, headFlags, skb.sacked(), skb.sentTimeUs());
        TcpSkb tailEntry = new TcpSkb(
                tailBuf, skb.startSeq() + limit, tailLen, tailFlags, skb.sacked(), skb.sentTimeUs());

        TcpSkb polled = sock.sendBuffer().pollRtx();
        if (polled != null) {
            polled.release();
        }
        sock.sendBuffer().enqueueRtxFirst(tailEntry);
        sock.sendBuffer().enqueueRtxFirst(headEntry);
        return true;
    }

    /** Not implemented; always {@code false}. @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_output.c#L2374">tcp_small_queue_check</a> */
    private boolean tcp_small_queue_check(TcpConnection conn, TcpSkb skb, int push_one) {
        return false;
    }

    private boolean tcp_small_queue_check(TcpSock sock, TcpSkb skb, int push_one) {
        return false;
    }

    /** @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_output.c#L1486">tcp_transmit_skb</a> */
    private int tcp_transmit_skb(TcpConnection conn, TcpSkb skb) {
        return __tcp_transmit_skb(conn, skb, conn.rcvNxt());
    }

    private int tcp_transmit_skb(TcpSock sock, TcpSkb skb) {
        return __tcp_transmit_skb(sock, skb, sock.rcvNxt());
    }

    /**
     * Build and write one data segment.
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_output.c#L1290">__tcp_transmit_skb</a>
     */
    private int __tcp_transmit_skb(TcpConnection conn, TcpSkb skb, int rcv_nxt) {
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

    private int __tcp_transmit_skb(TcpSock sock, TcpSkb skb, int rcv_nxt) {
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
    private byte[] tcp_established_options(TcpConnection conn, TcpSkb skb) {
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

    private byte[] tcp_established_options(TcpSock sock, TcpSkb skb) {
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
    private void tcp_event_new_data_sent(TcpSock sock, TcpSkb skb) {
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
    private void tcp_minshall_update(TcpConnection conn, int mss_now, TcpSkb skb) {
        if (skb.dataLen() < tcp_skb_pcount(skb) * mss_now) {
            conn.sndSml(skb.endSeq());
        }
    }

    private void tcp_minshall_update(TcpSock sock, int mss_now, TcpSkb skb) {
        if (skb.dataLen() < tcp_skb_pcount(skb) * mss_now) {
            sock.sndSml(skb.endSeq());
        }
    }

    /** Always 1 — no TSO/GSO. */
    private int tcp_skb_pcount(TcpSkb skb) {
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
     *
     * <p>Mirrors Linux {@code tcp_select_window} (tcp_output.c:260) in the parts that
     * are expressible with v2's existing socket fields:
     * <ul>
     *   <li><b>Never shrink the offered window</b> (when {@code rcv_wscale > 0}):
     *       if the newly selected {@code new_win} would fall below the window still
     *       advertised to the peer ({@code cur_win = rcv_wup + rcv_wnd - rcv_nxt}),
     *       round {@code cur_win} up to a {@code 1<<rcv_wscale} boundary and use it.</li>
     *   <li><b>RFC 1323 scaling cap</b>: before right-shifting by {@code rcv_wscale},
     *       clamp to {@code TCP_MAX_WINDOW} (wscale=0) or {@code U16_MAX << rcv_wscale}
     *       (wscale&gt;0) so the 16-bit on-wire field never overflows.</li>
     *   <li>Update {@code rcv_wup = rcv_nxt} and {@code rcv_wnd = new_win} only after the
     *       shrink guard, matching Linux ordering.</li>
     * </ul>
     *
     * <p>Not implemented (requires fields v2 does not yet carry — marked as D1 gap):
     * <ul>
     *   <li>{@code window_clamp / rcv_ssthresh / tcp_full_space / tcp_space} trade-off</li>
     *   <li>Zero-window thresholds ({@code free_space < allowed_space/16} or {@code &lt; mss})</li>
     *   <li>{@code rounddown(free_space, mss)} when {@code rcv_wscale == 0}</li>
     * </ul>
     */
    private static int selectAdvertisedWindow(TcpConnection conn) {
        final int curWin   = conn.tcp_receive_window();
        final int wscale   = conn.rcvWscale();
        int newWin = conn.rcvWnd();

        if (newWin < curWin && wscale != 0) {
            newWin = align(curWin, 1 << wscale);
        }

        conn.rcvWnd(newWin);
        conn.rcvWup(conn.rcvNxt());

        if (wscale == 0) {
            newWin = Math.min(newWin, TcpConstants.TCP_MAX_WINDOW);
        } else {
            newWin = Math.min(newWin, TcpConstants.U16_MAX << wscale);
        }
        newWin >>= wscale;
        return Math.max(newWin, 0);
    }

    /**
     * 对齐 Linux {@code __tcp_select_window} (tcp_output.c:3084) + {@code tcp_select_window}
     * 外层 no-shrink 包装的复合实现。
     *
     * <p>算法要点:
     * <ol>
     *   <li>{@code free_space = tcp_space(sk)},{@code allowed_space = tcp_full_space(sk)},
     *       {@code full_space = min(window_clamp, allowed_space)}。</li>
     *   <li><b>零窗阈值</b>:当 {@code free_space < full_space/2} 且
     *       ({@code free_space < allowed_space/16} 或 {@code free_space < mss}) 时通告 0。</li>
     *   <li><b>rcv_ssthresh 限幅</b>:{@code free_space = min(free_space, rcv_ssthresh)}。</li>
     *   <li><b>wscale == 0 的 rounddown(mss) 回退</b>:若 {@code rcv_wnd} 偏离 {@code free_space}
     *       超过一个 MSS,就地对齐到 MSS 边界;当 {@code mss == full_space} 且有充足空间时
     *       放大到 {@code free_space}。</li>
     *   <li><b>no-shrink 外层包装</b>:若新窗口小于已通告窗口且 {@code rcv_wscale != 0},
     *       回退到 {@code cur_win} 对齐 {@code 1<<wscale}(Linux {@code tcp_select_window})。</li>
     *   <li>最后按 {@code TCP_MAX_WINDOW} / {@code U16_MAX<<wscale} 截断并右移 wscale 得到线格表示。</li>
     * </ol>
     *
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_output.c#L3084">__tcp_select_window</a>
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_output.c#L260">tcp_select_window</a>
     */
    private static int selectAdvertisedWindow(TcpSock sock) {
        final int curWin = sock.tcp_receive_window();
        final int wscale = sock.rcvWscale();
        int mss = sock.rcvMss();
        int freeSpace = tcp_space(sock);
        int allowedSpace = tcp_full_space(sock);
        int fullSpace = Math.min(sock.windowClamp(), allowedSpace);

        if (mss <= 0) {
            mss = TcpConstants.TCP_MSS_DEFAULT;
        }
        if (mss > fullSpace) {
            mss = fullSpace;
        }

        // 零窗阈值(对齐 Linux:free_space < allowed_space/16 || free_space < mss)
        if (freeSpace < (fullSpace >> 1)) {
            if (freeSpace < (allowedSpace >> 4) || freeSpace < mss) {
                sock.rcvWnd(0);
                sock.rcvWup(sock.rcvNxt());
                return 0;
            }
        }

        // 内存压力下 clamp rcv_ssthresh 到 4*advmss — 对齐 Linux tcp_clamp_window 语义
        int effectiveRcvSsthresh = sock.rcvSsthresh();
        if (SysctlOptions.tcp_under_memory_pressure()) {
            int pressureCap = Math.max(mss, 1) * TcpConstants.TCP_PRESSURE_RCV_SSTHRESH_MSS_MULT;
            if (effectiveRcvSsthresh > pressureCap) {
                effectiveRcvSsthresh = pressureCap;
            }
        }
        // rcv_ssthresh 限幅
        if (freeSpace > effectiveRcvSsthresh) {
            freeSpace = effectiveRcvSsthresh;
        }

        int window;
        if (wscale != 0) {
            // 缩放路径:直接用 freeSpace,最终输出前按 U16_MAX<<wscale 截断
            window = freeSpace;
        } else {
            // 无缩放路径:尽量沿用 rcv_wnd,偏离过大时 rounddown 到 MSS 边界
            window = sock.rcvWnd();
            if (mss > 0) {
                if (window <= freeSpace - mss || window > freeSpace) {
                    window = roundDown(freeSpace, mss);
                } else if (mss == fullSpace && freeSpace > window + (fullSpace >> 1)) {
                    window = freeSpace;
                }
            } else if (window > freeSpace) {
                window = freeSpace;
            }
        }

        // no-shrink 外层保护(对齐 Linux tcp_select_window):wscale != 0 时不得收缩
        if (window < curWin && wscale != 0) {
            window = align(curWin, 1 << wscale);
        }

        sock.rcvWnd(window);
        sock.rcvWup(sock.rcvNxt());

        if (wscale == 0) {
            window = Math.min(window, TcpConstants.TCP_MAX_WINDOW);
        } else {
            window = Math.min(window, TcpConstants.U16_MAX << wscale);
        }
        window >>= wscale;
        return Math.max(window, 0);
    }

    /**
     * 对齐 Linux {@code tcp_win_from_space}(include/net/tcp.h):
     * {@code win = (space * scaling_ratio) >> TCP_RMEM_TO_WIN_SCALE}。
     * v2 取静态 {@link TcpConstants#TCP_DEFAULT_SCALING_RATIO}(≈ 0.8),
     * 未接入 {@code tcp_update_scaling_ratio} 的动态自适应。
     */
    private static int tcp_win_from_space(int space) {
        if (space <= 0) return 0;
        long win = ((long) space * TcpConstants.TCP_DEFAULT_SCALING_RATIO)
                >> TcpConstants.TCP_RMEM_TO_WIN_SCALE_SHIFT;
        return win > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) win;
    }

    /**
     * {@code tcp_full_space(sk) = tcp_win_from_space(sk, sk_rcvbuf)} — 当前接收缓冲区总预算
     * 转换为可通告窗口字节数。
     */
    private static int tcp_full_space(TcpSock sock) {
        return tcp_win_from_space(Math.max(sock.rcvBuf(), 0));
    }

    /**
     * {@code tcp_space(sk) = tcp_win_from_space(sk, sk_rcvbuf - sk_rmem_alloc - sk_backlog.len)}。
     * v2 的 {@code rmem_alloc}(OFO + in-order)已对齐 Linux {@code sk_rmem_alloc};
     * {@code sk_backlog.len} 未建模(v2 直接 EventLoop 派发,不存在软中断 backlog),近似忽略。
     */
    private static int tcp_space(TcpSock sock) {
        int used = (sock.hasConnection() && sock.receiveBuffer() != null)
                ? sock.receiveBuffer().rmemAlloc()
                : 0;
        return tcp_win_from_space(Math.max(sock.rcvBuf() - used, 0));
    }

    /** Round {@code n} down to the nearest multiple of {@code step} (step &gt; 0). */
    private static int roundDown(int n, int step) {
        if (step <= 0) return n;
        return n - (n % step);
    }

    /** Round {@code n} up to a {@code step}-byte boundary ({@code step} must be a power of 2). */
    private static int align(int n, int step) {
        return (n + step - 1) & ~(step - 1);
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

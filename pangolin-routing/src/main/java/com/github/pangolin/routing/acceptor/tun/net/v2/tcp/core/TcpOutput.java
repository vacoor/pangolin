package com.github.pangolin.routing.acceptor.tun.net.v2.tcp.core;

import com.github.pangolin.routing.acceptor.tun.net.codec.TcpPacketBuf;

import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.buffer.Unpooled;
import lombok.extern.slf4j.Slf4j;

import static com.github.pangolin.routing.acceptor.tun.net.handler.tcp.util.TcpClock.tcp_clock_ms;
import static com.github.pangolin.routing.acceptor.tun.net.handler.tcp.util.TcpClock.tcp_jiffies32;
import static com.github.pangolin.routing.acceptor.tun.net.v2.tcp.core.TcpConstants.*;

/**
 * TCP output operations — the <b>single point</b> of all outbound packet writes.
 *
 * <p>Mirrors Linux {@code net/ipv4/tcp_output.c}: every packet that leaves via
 * the TUN interface originates here. No other component calls {@code writeRaw()} or
 * {@code writeAndFlush()} directly.
 *
 * <p>Responsibilities by section:
 * <ul>
 *   <li><b>Established-state sends</b> ({@link TcpSock} available):
 *       {@link #sendAck}, {@link #sendChallengeAck},
 *       {@link #writeXmit}, {@link #sendFin},
 *       {@link #sendReset}, {@link #retransmitSkb}.</li>
 *   <li><b>Handshake sends</b> (no {@link TcpSock} yet, raw params):
 *       {@link #sendSynack}, {@link #sendChallengeAckHandshake},
 *       {@link #sendResetHandshake}.</li>
 *   <li><b>Stateless RST</b> (listen path, no connection):
 *       {@link #v4SendReset}.</li>
 * </ul>
 *
 * <p>Stateless — all mutable state lives in {@link TcpSock}/{@link Sender}.
 */
@Slf4j
public final class TcpOutput {

    /**
     * 进程级 fallback 单例。R1.4(2026-04-23):TcpOutput 已经降为 SegmentDispatcher
     * 持有的 per-stack 实例(见 {@link SegmentDispatcher#output()}),本 INSTANCE 仅为
     * 过渡期保留,供还不能拿到 stack 引用的外部 adapter(TcpMultiplexHandler
     * 的 fallback ingress reset 等)暂时使用。后续所有主干路径都应走 per-stack 实例。
     */
    public static final TcpOutput INSTANCE = new TcpOutput();

    public TcpOutput() {}

    // ─── Host-wide Challenge ACK limiter (RFC 5961 §7) ─────────────────────
    // 对齐 Linux net->ipv4.tcp_challenge_timestamp / tcp_challenge_count(per-netns 桶,
    // 阈值 sysctl_tcp_challenge_ack_limit 默认 1000/s)。Linux 4.x+ 把 challenge ACK
    // 计数从 per-socket 移到了 netns 层,v2 这两个静态字段就是该桶的对应实现。
    private static final long CHALLENGE_ACK_WINDOW_MS = 1000L;
    private long challengeAckWindowStartMs = 0L;
    private int  challengeAckCount         = 0;

    /**
     * Host-wide Challenge ACK token check. Mirrors Linux {@code tcp_ack_update_window}'s
     * global counter path: refresh the window when it rolls over, then decide whether to
     * grant a new token. Thread-safety: v2 绑定单 EventLoop,此状态无并发写入。
     *
     * @return {@code true} 允许发送;{@code false} 超限,丢弃
     */
    private boolean challengeAckHostAllow() {
        long now = System.currentTimeMillis();
        if (now - challengeAckWindowStartMs >= CHALLENGE_ACK_WINDOW_MS) {
            challengeAckWindowStartMs = now;
            challengeAckCount = 0;
        }
        if (challengeAckCount >= SysctlOptions.sysctl_tcp_challenge_ack_limit) {
            return false;
        }
        challengeAckCount++;
        return true;
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Established-state sends  (have TcpSock)
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Send a pure ACK (no data).
     *
     * <p>Mirrors Linux {@code __tcp_send_ack}: builds an SKB that is <em>not</em> placed on the
     * write queue, then transmits immediately. After the write,
     * {@link #eventAckSent} is called — clearing {@code ACK_SCHED | ACK_TIMER} and
     * cancelling the delayed-ACK timer — exactly as Linux {@code __tcp_transmit_skb} does on
     * every outbound segment.
     *
     * <p>Seq = {@code SND.NXT} (= {@code tcp_acceptable_seq} for non-URG),
     * Ack = {@code RCV.NXT}.
     *
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_output.c">__tcp_send_ack</a>
     */
    public void sendAck(TcpSock sock) {
        if (sock == null || !sock.hasConnection()) {
            return;
        }
        final int rcv_nxt = sock.receiver().rcvNxt();
        FourTuple ft = sock.fourTuple();
        int wnd = selectAdvertisedWindow(sock);
        byte[] options = buildAckOptions(sock);
        ByteBuf buf = TcpPacketBuilder.buildRaw(
                ft.dstAddrBytes(), ft.dstPort(),
                ft.srcAddrBytes(), ft.srcPort(),
                sock.sndNxt(), rcv_nxt,
                TCPHDR_ACK, wnd, options, null, 0);
        sock.channel().writeAndFlush(buf);
        eventAckSent(sock, rcv_nxt);
    }

    /**
     * 组装 pure ACK 段的 TCP 选项 — 对齐 Linux {@code __tcp_send_ack} 中
     * {@code tcp_options_write} 的 TSopt + SACK 分支。
     *
     * <p>选项顺序(与 Linux 一致):TSopt(若协商)→ SACK(含 DSACK)。最多 4 个 SACK 块
     * (RFC 2018 §3 上限)。若存在待发 DSACK,放在 SACK 块首位(RFC 2883 §4)。
     *
     * <p>此方法仅作用于 pure ACK 段(不携带数据),因此 SACK 追加带来的 34 字节选项空间
     * 不会影响数据段的 MSS 计算 — 数据段走的是 {@code establishedOptions(sock, skb)},
     * 不含 SACK。
     */
    private byte[] buildAckOptions(TcpSock sock) {
        final boolean hasTs = sock.timestampEnabled();
        final int[] dsack = new int[2];
        final boolean hasDsack = sock.consumeDsack(dsack);
        final int maxSackBlocks = hasDsack ? 3 : 4;
        final int[] sackScratch = new int[2 * maxSackBlocks];
        final int sackCount = sock.receiver().buffer().computeSackBlocks(sackScratch, maxSackBlocks);
        final int totalBlocks = (hasDsack ? 1 : 0) + sackCount;

        if (!hasTs && totalBlocks == 0) {
            return null;
        }

        int capacity = 0;
        if (hasTs) capacity += 12;
        if (totalBlocks > 0) capacity += 2 + 2 + 8 * totalBlocks;
        ByteBuf buf = Unpooled.buffer(capacity);
        try {
            if (hasTs) {
                long tsval = tcp_clock_ms() & 0xFFFFFFFFL;
                long tsecr = ((long) sock.recentTimestamp()) & 0xFFFFFFFFL;
                com.github.pangolin.routing.acceptor.tun.net.handler.tcp.util.TcpOptionCodec
                        .writeTimestampOption(buf, tsval, tsecr);
            }
            if (totalBlocks > 0) {
                int[] blocks = new int[2 * totalBlocks];
                int idx = 0;
                if (hasDsack) {
                    blocks[idx++] = dsack[0];
                    blocks[idx++] = dsack[1];
                }
                for (int i = 0; i < sackCount; i++) {
                    blocks[idx++] = sackScratch[2 * i];
                    blocks[idx++] = sackScratch[2 * i + 1];
                }
                com.github.pangolin.routing.acceptor.tun.net.handler.tcp.util.TcpOptionCodec
                        .writeSackOption(buf, blocks, totalBlocks);
            }
            byte[] out = new byte[buf.readableBytes()];
            buf.readBytes(out);
            return out;
        } finally {
            buf.release();
        }
    }

    /**
     * Send a challenge ACK (RFC 5961 §7 / RFC 9293 §3.5.3 ACK throttling).
     *
     * <p>Mirrors Linux {@code tcp_send_challenge_ack} (tcp_input.c):
     * <ol>
     *   <li><b>Netns-level rate limit</b> — {@link #challengeAckHostAllow()} 对齐 Linux
     *       {@code net->ipv4.tcp_challenge_count} 桶,阈值为
     *       {@code sysctl_tcp_challenge_ack_limit}(默认 1000/s)。Linux 自 4.x 起把
     *       该计数从 per-socket({@code tp->challenge_*})移到 netns 层,v2 用静态字段
     *       {@code challengeAckWindowStartMs}/{@code challengeAckCount} 模拟。</li>
     *   <li>Send a pure ACK via {@link #sendAck}。</li>
     * </ol>
     *
     * <p>注意:不读写 {@code tp->last_oow_ack_time}({@code TcpSock.lastOowAckTimeMs}),
     * 后者是 {@link TcpOutOps#oowRateLimited} 专用的 per-socket OOW 桶,与 challenge
     * ACK 是两套独立的限速机制(对齐 Linux 行为)。
     *
     * <p>{@code accecn_reflector}: reserved for AccECN support (RFC 9331); not implemented.
     *
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_input.c">sendChallengeAck</a>
     */
    public void sendChallengeAck(TcpSock sock, boolean accecn_reflector) {
        if (sock == null || !sock.hasConnection()) {
            return;
        }
        if (!challengeAckHostAllow()) {
            return;
        }
        sock.stack().mib().inc(TcpMib.TCPCHALLENGEACK);
        sendAck(sock);
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
    public void sendReset(TcpSock sock) {
        if (sock == null || !sock.hasConnection()) {
            return;
        }
        FourTuple ft = sock.fourTuple();
        ByteBuf buf = TcpPacketBuilder.buildRaw(
                ft.dstAddrBytes(), ft.dstPort(),
                ft.srcAddrBytes(), ft.srcPort(),
                sock.sndNxt(), sock.receiver().rcvNxt(),
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
     *       {@link #eventAckSent} to piggy-back the ACK and clear delayed-ACK state.</li>
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
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_output.c#L3448">retransmitSkb</a>
     */
    public void retransmitSkb(TcpSock sock) {
        if (sock == null || !sock.hasConnection()) {
            return;
        }
        // (0) 选段:若 RACK/FACK 已标记 LOST,优先重传 LOST 段;否则退回到 RTX 头段
        //     对齐 Linux {@code tcp_xmit_retransmit_queue} 的 LOST 驱动 + 首段兜底。
        TcpSegment oldest = null;
        if (sock.lostOut() > 0) {
            for (TcpSegment skb : sock.sendBuffer().rtxView()) {
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

        // (3) fragment on RTX queue when skb->len > avail_wnd (Linux __tcp_retransmit_skb):
        //     对端窗口已收缩到段以下时,在 avail_wnd 边界切分,仅重传头段。
        //     头段对齐 MSS;若 avail_wnd 不足 1 MSS 但段仍在 SND.UNA,保留整段作为零窗探测。
        if (availWnd > 0 && oldest.dataLen() > availWnd) {
            final int mss = Math.max(sock.mss(), 1);
            int limit = (availWnd >= mss) ? (availWnd / mss) * mss : availWnd;
            if (limit > 0 && limit < oldest.dataLen()) {
                if (rtxFragment(sock, oldest, limit)) {
                    oldest = sock.sendBuffer().peekRtx();
                    if (oldest == null) {
                        return;
                    }
                }
            }
        }

        // (3.5) tcp_collapse_retrans:重传前若段严重小于 MSS 且相邻段合并后仍 ≤ MSS,
        //       将两段合并成一个,降低段开销 / ACK 噪声。对齐 Linux
        //       __tcp_retransmit_skb 中 tcp_collapse_retrans 的尝试点(in_flight 较小、
        //       段未被 SACK 时生效)。
        if (!oldest.isSackAcked() && oldest.dataLen() < sock.mss()) {
            com.github.pangolin.routing.acceptor.tun.net.v2.tcp.core.TcpSendBuffer.CollapseResult cr
                    = sock.sendBuffer().collapseRtx(oldest, sock.mss());
            if (cr != null) {
                // 对齐 Linux tcp_adjust_pcount:合并吞掉 next 段,packetsOut/lostOut/
                // retransOut 按 next 的 sacked 位集递减。
                sock.decrementPacketsOut(1);
                if ((cr.droppedSacked & com.github.pangolin.routing.acceptor.tun.net.v2.tcp.core.TcpConstants.TCPCB_LOST) != 0) {
                    sock.decrLostOut(1);
                }
                if ((cr.droppedSacked & com.github.pangolin.routing.acceptor.tun.net.v2.tcp.core.TcpConstants.TCPCB_SACKED_RETRANS) != 0) {
                    sock.decrRetransOut(1);
                }
                oldest = cr.merged;
            }
        }

        // (4) retrans_stamp / undo_retrans 统计:首次进入重传路径打时戳,
        //     每个段首次被标记为 retransmitted 时 undo_retrans++(对齐 Linux
        //     __tcp_retransmit_skb: if (!tp->retrans_stamp) tp->retrans_stamp = ...;
        //     if (!err) tp->undo_retrans += pcount;)。
        final long nowUs = System.nanoTime() / 1_000L;
        if (sock.sender().retransStamp() == 0L) {
            sock.sender().retransStamp(nowUs);
        }
        if (!oldest.isRetransmitted()) {
            sock.incrUndoRetrans();
            // 对齐 Linux __tcp_retransmit_skb:首次给段打 TCPCB_SACKED_RETRANS 时
            // tp->retrans_out += pcount。同段后续重传不再重复计数(段已带 RETRANS 标记)。
            // 由 TcpAck.cleanRtxQueue 在累计 ACK 释放该段时同步 -- 抵消;
            // RTO 进入 Loss 时由 Sender.onTimeoutByCc 整体清零。
            sock.incrRetransOut();
        }

        oldest.markRetransmitted();
        oldest.updateSentTime(nowUs);
        // 对齐 Linux tcp_rate_skb_sent:重传时同样重置 tx.delivered 为当前 tp->delivered,
        // 让本次重传在被 ACK 时参与最新 rs->prior_delivered 评估(而非沿用原始发送快照)。
        oldest.txDelivered(sock.delivered());
        sock.stack().mib().inc(TcpMib.TCPRETRANSSEGS);

        if (log.isInfoEnabled()) {
            log.info("[TCP-RETRX] {} retransmitSkb seq={} len={} sndUna={} sndNxt={}"
                            + "lostOut={} sackedOut={} retransOut={} packetsOut={} cwnd={} caState={}",
                    sock.fourTuple(),
                    Integer.toUnsignedString(oldest.startSeq()),
                    oldest.dataLen(),
                    Integer.toUnsignedString(sock.sndUna()),
                    Integer.toUnsignedString(sock.sndNxt()),
                    sock.lostOut(), sock.sackedOut(), sock.sender().retransOut(),
                    sock.packetsOut(), sock.cwnd(), sock.sender().congestionState(),
                    new Throwable("[TCP-RETRX] caller stack"));
        }

        __tcp_transmit_skb(sock, oldest, sock.receiver().rcvNxt());
    }

    // ── writeXmit and sendFin ───────────────────────────────────

    /**
     * Drain the send buffer: segment and transmit pending data within the current
     * congestion window and peer receive window.
     *
     * @param sock     the socket whose send buffer to drain
     * @param mss_now  current MSS (≈ {@code currentMss()})
     * @param nonagle  Nagle flags ({@link TcpConstants#TCP_NAGLE_OFF} etc.)
     * @param push_one 0 = send as many as allowed; 1 = at most one; 2 = force loss probe
     * @return {@code true} if data is queued but the send window is closed and no segments
     *         are in flight — caller should arm the persist/probe timer;
     *         {@code false} otherwise
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_output.c#L2791">writeXmit</a>
     */
    public boolean writeXmit(TcpSock sock, int mss_now, int nonagle, int push_one) {
        if (sock == null || !sock.hasConnection()) {
            return false;
        }
        // enterXmit/exitXmit 是给 EmbeddedChannel 路径的 re-entry 守卫:
        // writeAndFlush 内部 maybeRunPendingTasks 若 inline fire 到 TLP/RTO
        // callback,看到 isInXmit() 就 skip,避免陈旧 tcpSendHead 导致的双入队。
        // 生产 NIO 下调度器不会 inline fire,这一对 enter/exit 是纯 no-op。
        sock.sender().enterXmit();
        try {
            return writeXmitLocked(sock, mss_now, nonagle, push_one);
        } finally {
            sock.sender().exitXmit();
        }
    }

    private boolean writeXmitLocked(TcpSock sock, int mss_now, int nonagle, int push_one) {
        int sent_pkts = 0;
        boolean is_cwnd_limited = false;
        boolean is_rwnd_limited = false;

        // tcp_slow_start_after_idle_check:空闲 > RTO 后 burst 前先回退 cwnd 到 TCP_INIT_CWND。
        // Linux 在 sendmsgLocked / tcp_push_one 路径上游调用,v2 合并到 writeXmit 入口。
        sock.tcpSlowStartAfterIdleCheck();

        mstampRefresh(sock);

        if (push_one == 0) {
            int mtu = mtuProbe(sock);
            if (mtu == 0) {
                return false;
            } else if (mtu > 0) {
                sent_pkts = 1;
            }
        }

        final int max_segs = tsoSegs(sock, mss_now);
        TcpSegment skb;
        while ((skb = sock.tcpSendHead()) != null) {
            if (pacingCheck(sock)) {
                break;
            }

            int cwnd_quota = cwndTest(sock);
            if (cwnd_quota == 0) {
                if (push_one == 2) {
                    cwnd_quota = 1;
                } else {
                    is_cwnd_limited = true;
                    break;
                }
            }
            cwnd_quota = Math.min(cwnd_quota, max_segs);

            final int tso_segs = setSkbTsoSegs(skb, mss_now);
            if (!sndWndTest(sock, skb, mss_now)) {
                is_rwnd_limited = true;
                break;
            }

            if (tso_segs == 1) {
                if (!nagleTest(sock, skb, mss_now,
                        skbIsLast(sock, skb) ? nonagle : TCP_NAGLE_PUSH)) {
                    break;
                }
            } else {
                if (push_one == 0
                        && tsoShouldDefer(sock, skb, is_cwnd_limited, is_rwnd_limited, max_segs)) {
                    break;
                }
            }

            if (skb.dataLen() == 0 && !skb.isFin() && !skb.isSyn()) {
                break;
            }

            int limit = mss_now;
            if (tso_segs > 1 && !urgMode(sock)) {
                limit = mssSplitPoint(sock, skb, mss_now, cwnd_quota, nonagle);
            }

            if (skb.dataLen() > limit) {
                if (fragment(sock, skb, limit, mss_now)) {
                    break;
                }
                skb = sock.tcpSendHead();
                if (skb == null) {
                    break;
                }
            }

            if (smallQueueCheck(sock, skb, push_one)) {
                break;
            }

            if (0 != transmitSkb(sock, skb)) {
                break;
            }

            eventNewDataSent(sock, skb);
            minshallUpdate(sock, mss_now, skb);
            sent_pkts += skbPcount(skb);

            if (push_one != 0) {
                break;
            }
        }

        is_cwnd_limited |= (sock.packetsOut() >= sock.cwnd());
        if (sent_pkts != 0 || is_cwnd_limited) {
            sock.tcpCwndValidate(is_cwnd_limited);
        }
        if (sent_pkts != 0) {
            if (inCwndReduction(sock)) {
            }
            if (push_one != 2) {
                scheduleLossProbe(sock);
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
     * ({@link #writeXmit}) to transmit all pending segments including the FIN.
     *
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_output.c#L3442">sendFin</a>
     */
    public void sendFin(TcpSock sock) {
        if (sock == null || !sock.hasConnection()) {
            return;
        }
        ByteBuf empty = sock.channel().alloc().buffer(0, 0);
        sock.queueSkb(new TcpSegment(
                empty, sock.writeSeq(), 0, (byte) (TCPHDR_ACK | TCPHDR_FIN), 0L));
        writeXmit(sock, sock.mss(), TCP_NAGLE_OFF, 0);
    }

    public int writeWakeup(TcpSock sock, int mib) {
        if (sock == null || !sock.hasConnection() || sock.state() == com.github.pangolin.routing.acceptor.tun.net.v2.tcp.core.TcpConnectionState.TCP_CLOSED) {
            return -1;
        }

        TcpSegment skb = sock.tcpSendHead();
        int wndEnd = sock.sndUna() + sock.sndWnd();
        if (skb != null && TcpSequence.before(skb.startSeq(), wndEnd)) {
            int priorPackets = sock.packetsOut();
            long priorSndNxt = Integer.toUnsignedLong(sock.sndNxt());
            writeXmit(sock, currentMss(sock), TCP_NAGLE_OFF, 1);
            return (sock.packetsOut() != priorPackets || Integer.toUnsignedLong(sock.sndNxt()) != priorSndNxt) ? 0 : 0;
        }
        return xmitProbeSkb(sock, 0, mib);
    }

    public long sendProbe0(TcpSock sock) {
        if (sock == null || !sock.hasConnection()) {
            return -1L;
        }
        int err = writeWakeup(sock, 0);

        Sender sender = sock.sender();
        if (sock.packetsOut() > 0 || sock.tcpSendHead() == null) {
            sender.resetProbeState();
            return -1L;
        }

        sender.probesOut(sender.probesOut() + 1);
        long timeout;
        if (err <= 0) {
            if (sender.probeBackoffShift() < TcpConstants.TCP_RETRIES2) {
                sender.incProbeBackoff();
            }
            timeout = sender.tcpProbe0WhenMs(TcpConstants.RTO_MAX_MS);
        } else {
            timeout = TcpConstants.TCP_RESOURCE_PROBE_INTERVAL_MS;
        }

        timeout = sender.tcpClampProbe0ToUserTimeout(timeout);
        return timeout;
    }

    public void sendLossProbe(TcpSock sock) {
        if (sock == null || !sock.hasConnection()) {
            return;
        }
        if (log.isInfoEnabled()) {
            log.info("[TCP-TLP] {} sendLossProbe sndUna={} sndNxt={} packetsOut={}"
                            + "lostOut={} retransOut={} sendHead={} rtxHead={} caState={}",
                    sock.fourTuple(),
                    Integer.toUnsignedString(sock.sndUna()),
                    Integer.toUnsignedString(sock.sndNxt()),
                    sock.packetsOut(), sock.lostOut(), sock.sender().retransOut(),
                    sock.tcpSendHead() != null,
                    sock.sendBuffer() != null && sock.sendBuffer().peekRtx() != null,
                    sock.sender().congestionState());
        }
        if (sock.tcpSendHead() != null) {
            writeXmit(sock, currentMss(sock), TCP_NAGLE_OFF, 2);
            return;
        }
        if (sock.sendBuffer() != null && sock.sendBuffer().peekRtx() != null) {
            retransmitSkb(sock);
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Handshake sends  (no TcpSock yet, raw addressing)
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
    public ChannelFuture sendSynack(Channel ch,
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
     * <p>握手期无 {@link TcpSock},因此只受 host 全局令牌桶约束 — 对齐 Linux
     * {@code sysctl_tcp_challenge_ack_limit} 对所有 Challenge-ACK 通路的统一约束,
     * 避免跨 4 元组放大攻击。
     *
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_input.c#L3649">sendChallengeAck</a>
     */
    public void sendChallengeAckHandshake(Channel ch,
            byte[] localIp, int localPort, byte[] remoteIp, int remotePort,
            int seq, int ack, int window) {
        if (!challengeAckHostAllow()) {
            return;
        }
        // handshake 期路径无 sock 引用,沿用 INSTANCE 全局 MIB — R1.4(TcpOutput
        // 降为 SegmentDispatcher 实例字段)将统一到 per-stack mib。
        TcpMibStats.INSTANCE.inc(TcpMib.TCPCHALLENGEACK);
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
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_output.c#L3615">sendReset</a>
     */
    public ChannelFuture sendResetHandshake(Channel ch,
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
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_ipv4.c#L711">v4SendReset</a>
     */
    /**
     * 对齐 Linux {@code tcp_v4_timewait_ack}(net/ipv4/tcp_ipv4.c):对 TIME_WAIT bucket 中
     * 迟到 FIN / 数据段重放 ACK。无关联 {@link TcpSock},全部字段
     * 来自 {@link TcpTimewaitSock} 快照。
     *
     * <p>出口:优先使用 twsk 保留的 TUN 侧 channel;channel 若已失效,回退到调用点
     * 提供的 {@link ChannelHandlerContext}。
     */
    public void timewaitSendAck(ChannelHandlerContext ctx, TcpTimewaitSock tw) {
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

    public void v4SendReset(ChannelHandlerContext ctx, TcpPacketBuf pkt) {
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
    // writeXmit internals  (mirrors tcp_output.c sub-functions)
    // ═══════════════════════════════════════════════════════════════════════

    /** @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_output.c#L55">mstampRefresh</a> */
    private void mstampRefresh(TcpSock sock) {
        // v2 does not cache tcp_clock_cache/tcp_mstamp separately; RTT uses per-segment stamps.
    }

    /**
     * MTU probing — not implemented.
     * @return -1 (skip probe); 0 = early-return false; >0 = probe sent
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_output.c#L2434">mtuProbe</a>
     */
    private int mtuProbe(TcpSock sock) {
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
     *       触发 {@link #syncMss} 以新 PMTU 重新推导 {@code mssCache},对应
     *       Linux 的 <code>if (mtu != icsk->icsk_pmtu_cookie) mss_now = syncMss(...)</code>。</li>
     *   <li>计算 {@code header_len = TCP_HDR + established_options_len};若与
     *       {@code sock.tcpHeaderLen()} 存在 delta,从 MSS 中扣减,对应
     *       Linux 的 <code>mss_now -= (header_len - tp->tcp_header_len)</code>。</li>
     * </ol>
     *
     * <p>v2 目前仅 TSopt 一种已协商的 ESTABLISHED 选项(SACK 块按段动态追加尚未启用),
     * 因此运行期 delta 在不启用 SACK 的路径下恒为 0。{@link #establishedOptionsLen}
     * 作为单一入口,后续引入 SACK 块后只需更新此处。
     *
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_output.c#L1865">currentMss</a>
     */
    public int currentMss(TcpSock sock) {
        if (sock == null) {
            return TcpConstants.TCP_MSS_DEFAULT;
        }
        int mss_now = sock.mssCache() > 0 ? sock.mssCache()
                : (sock.mss() > 0 ? sock.mss() : TcpConstants.TCP_MSS_DEFAULT);

        // PMTU 变化检测:dst_mtu 与缓存 cookie 不一致时重新同步
        int mtu = sock.dstMtu();
        if (mtu > 0 && mtu != sock.pmtuCookie()) {
            mss_now = syncMss(sock, mtu);
        }

        // Option-length delta:握手期定型 tcp_header_len,运行期选项长度变化从 MSS 扣减
        int header_len = TcpConstants.TCP_MIN_HEADER_LEN + establishedOptionsLen(sock);
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
    private int establishedOptionsLen(TcpSock sock) {
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
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_output.c#L1840">syncMss</a>
     */
    public int syncMss(TcpSock sock, int pmtu) {
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
        mss = boundToHalfWnd(sock, mss);
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
    private static int boundToHalfWnd(TcpSock sock, int mss) {
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
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_output.c#L2040">tsoSegs</a>
     */
    private int tsoSegs(TcpSock sock, int mss_now) {
        return Integer.MAX_VALUE;
    }

    /**
     * Pacing gate — pacing not implemented; always {@code false}.
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_output.c#L2769">pacingCheck</a>
     */
    private boolean pacingCheck(TcpSock sock) {
        return false;
    }

    /**
     * 返回本次 {@code tcp_write_xmit} 迭代允许吐出的段配额。
     *
     * <p>基线逻辑与 Linux {@code tcp_cwnd_test} 一致:{@code cwnd - in_flight},其中
     * {@code in_flight} 取 {@link TcpSock#packetsInFlight()}(对齐 Linux
     * {@code tcp_packets_in_flight} = {@code packets_out - sacked_out - lost_out}
     * + {@code retrans_out};v2 未维护 {@code retrans_out})。SACKed / LOST 段不占
     * cwnd 配额,避免 Recovery 期间窗口被双倍扣减。
     *
     * <p>在基线为 0 时追加 RFC 3042 Limited Transmit 分支:{@code CA_Open} 且
     * {@code dupacks ∈ [1, 2]} 时,把 dupack 计数当作额外配额,保证前两个 dupack
     * 各带出 1 个新段,维持 ACK clock,减少 FR/RTO 概率。在 NewReno 无 SACK 场景下
     * 这条分支等价 Linux {@code tcp_add_reno_sack} 的 {@code sacked_out++} 补偿。
     *
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_output.c#L2303">cwndTest</a>
     * @see <a href="https://datatracker.ietf.org/doc/html/rfc3042">RFC 3042 Limited Transmit</a>
     */
    private int cwndTest(TcpSock sock) {
        final int cwnd = sock.cwnd();
        final int in_flight = sock.packetsInFlight();
        int bonus = 0;
        // RFC 3042 Limited Transmit:OPEN 与 DISORDER(收到 1~2 个 dupack)都允许
        // 多发 1~2 段,助 fast retransmit 预热;Recovery / Loss 不放行(对齐 Linux
        // !tcp_in_cwnd_reduction 等价过滤)。
        if (sock.isCaOpen() || sock.inDisorder()) {
            final int dupacks = sock.dupacks();
            if (dupacks >= 1) {
                bonus = Math.min(dupacks, 2);
            }
        }
        final int quota = Math.max(0, cwnd + bonus - in_flight);
        if (bonus > 0 && in_flight >= cwnd && quota > 0) {
            // 真正用到 Limited Transmit 额度(基线 cwnd - in_flight 已耗尽),才计入 MIB。
            sock.stack().mib().inc(TcpMib.TCPLIMITEDTRANSMIT);
        }
        return quota;
    }

    /** Always 1 — no TSO/GSO. @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_output.c#L1614">setSkbTsoSegs</a> */
    private int setSkbTsoSegs(TcpSegment skb, int mss_now) {
        return 1;
    }

    /** @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_output.c#L1985">sndWndTest</a> */
    private boolean sndWndTest(TcpSock sock, TcpSegment skb, int mss_now) {
        int end_seq = skb.startSeq() + Math.min(skb.dataLen(), mss_now);
        int wnd_end = sock.sndUna() + sock.sndWnd();
        return !TcpSequence.after(end_seq, wnd_end);
    }

    /** @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_output.c#L1780">skbIsLast</a> */
    private boolean skbIsLast(TcpSock sock, TcpSegment skb) {
        return sock.sendBuffer().writeQueueSize() == 1;
    }

    /** @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_output.c#L1785">nagleTest</a> */
    private boolean nagleTest(TcpSock sock, TcpSegment skb, int mss_now, int nonagle) {
        if ((nonagle & (TCP_NAGLE_OFF | TCP_NAGLE_PUSH)) != 0) {
            return true;
        }
        return skb.dataLen() >= mss_now || sock.packetsOut() == 0;
    }

    /** TSO deferral — not implemented; always {@code false}. @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_output.c#L2065">tsoShouldDefer</a> */
    private boolean tsoShouldDefer(TcpSock sock, TcpSegment skb,
                                         boolean is_cwnd_limited, boolean is_rwnd_limited,
                                         int max_segs) {
        return false;
    }

    /** Urgent-mode — not implemented; always {@code false}. @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_output.c#L1993">urgMode</a> */
    private boolean urgMode(TcpSock sock) {
        return false;
    }

    /** Simplified: always {@code mss_now}. @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_output.c#L1741">mssSplitPoint</a> */
    private int mssSplitPoint(TcpSock sock, TcpSegment skb, int mss_now,
                                    int cwnd_quota, int nonagle) {
        return mss_now;
    }

    /**
     * Split write-queue head at {@code limit} bytes.
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_output.c#L1559">fragment</a>
     */
    private boolean fragment(TcpSock sock, TcpSegment skb, int limit, int mss_now) {
        ByteBuf origPayload = skb.payload();
        int origReader = origPayload.readerIndex();

        ByteBuf headBuf = origPayload.retainedSlice(origReader, limit);
        int tailLen = skb.dataLen() - limit;
        ByteBuf tailBuf = origPayload.retainedSlice(origReader + limit, tailLen);

        byte headFlags = (byte) (skb.tcpFlags() & ~(TCPHDR_FIN | TCPHDR_PSH));
        byte tailFlags = skb.tcpFlags();

        TcpSegment headEntry = new TcpSegment(headBuf, skb.startSeq(), limit, headFlags, 0L);
        TcpSegment tailEntry = new TcpSegment(tailBuf, skb.startSeq() + limit, tailLen, tailFlags, 0L);

        sock.sendBuffer().pollWrite().release();
        sock.sendBuffer().enqueueWriteFirst(tailEntry);
        sock.sendBuffer().enqueueWriteFirst(headEntry);
        return false;
    }

    /**
     * 就地切分 RTX 队列头段 — 对应 Linux {@code fragment(sk, TCP_FRAG_IN_RTX_QUEUE, skb, limit, mss, GFP_ATOMIC)}.
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
     *   <li>{@code sentTimeUs} 同样继承(重传调用者会在随后 {@link TcpSegment#updateSentTime}
     *       更新 head 的时间戳);</li>
     *   <li>切分后的两段按 head → tail 顺序插回队首,维持 seq 升序。</li>
     * </ul>
     *
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_output.c#L1559">fragment</a>
     */
    private boolean rtxFragment(TcpSock sock, TcpSegment skb, int limit) {
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

        TcpSegment headEntry = new TcpSegment(
                headBuf, skb.startSeq(), limit, headFlags, skb.sacked(), skb.sentTimeUs());
        TcpSegment tailEntry = new TcpSegment(
                tailBuf, skb.startSeq() + limit, tailLen, tailFlags, skb.sacked(), skb.sentTimeUs());
        headEntry.txDelivered(skb.txDelivered());
        tailEntry.txDelivered(skb.txDelivered());

        TcpSegment polled = sock.sendBuffer().pollRtx();
        if (polled != null) {
            polled.release();
        }
        sock.sendBuffer().enqueueRtxFirst(tailEntry);
        sock.sendBuffer().enqueueRtxFirst(headEntry);
        return true;
    }

    /** Not implemented; always {@code false}. @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_output.c#L2374">smallQueueCheck</a> */
    private boolean smallQueueCheck(TcpSock sock, TcpSegment skb, int push_one) {
        return false;
    }

    /** @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_output.c#L1486">transmitSkb</a> */
    private int transmitSkb(TcpSock sock, TcpSegment skb) {
        return __tcp_transmit_skb(sock, skb, sock.receiver().rcvNxt());
    }

    /**
     * Build and write one data segment.
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_output.c#L1290">__tcp_transmit_skb</a>
     */
    private int __tcp_transmit_skb(TcpSock sock, TcpSegment skb, int rcv_nxt) {
        if (skb == null) {
            return -1;
        }

        final byte[] rawOptions = establishedOptions(sock, skb);
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

        // 诊断日志(仅 TRACE 级别活跃 — 生产/UT 默认 INFO/DEBUG 不打开):
        // 对每个 data 段的 wire-level 发送都打一行 + caller stack。与 wireshark
        // 抓到的每个 outbound 段一一对应,确认是首发还是重传。
        // 注意:stack trace 创建 + IO 输出会显著放大 sentTime 间隔,可能让 RACK
        // 反向把先发段误判为 LOST。所以 gate 在 TRACE,UT 跑不到。生产复现时
        // 临时把 log4j 的 com.github.pangolin 调到 TRACE 即可启用。
        if (skb.dataLen() > 0 && log.isTraceEnabled()) {
            log.trace("[TCP-WIRE-OUT] {} {} seq={} len={} sndUna={} sndNxt={} packetsOut={} caState={}",
                    ft,
                    skb.isRetransmitted() ? "RETRX" : "FIRST",
                    Integer.toUnsignedString(skb.startSeq()),
                    skb.dataLen(),
                    Integer.toUnsignedString(sock.sndUna()),
                    Integer.toUnsignedString(sock.sndNxt()),
                    sock.packetsOut(),
                    sock.sender().congestionState(),
                    new Throwable("[TCP-WIRE-OUT] caller stack"));
        }

        sock.channel().writeAndFlush(buf);
        eventAckSent(sock, rcv_nxt);

        if (skb.dataLen() > 0) {
            eventDataSent(sock);
        }
        return 0;
    }

    /**
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_output.c#L163">eventDataSent</a>
     */
    private void eventDataSent(TcpSock sock) {
        sock.onDataSent();
    }

    /**
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_output.c#L633">establishedOptions</a>
     */
    private byte[] establishedOptions(TcpSock sock, TcpSegment skb) {
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
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_output.c#L2595">eventNewDataSent</a>
     */
    private void eventNewDataSent(TcpSock sock, TcpSegment skb) {
        sock.sndNxt(skb.endSeq());

        sock.sendBuffer().pollWrite();
        final boolean startRto = !sock.sendBuffer().hasRtxPending();
        skb.updateSentTime(System.nanoTime() / 1_000L);
        // 对齐 Linux tcp_rate_skb_sent:发送瞬间把 tp->delivered 打戳到 TCP_SKB_CB(skb)->tx.delivered,
        // 供 tcp_rate_gen / tcp_rack_update_reo_wnd 的 1-RTT 门控读回本段"发出时的投递水位"。
        skb.txDelivered(sock.delivered());
        sock.sendBuffer().enqueueRtx(skb);

        sock.incrementPacketsOut();
        // 对齐 Linux eventNewDataSent 内的 snd_cwnd_used 高水位刷新:
        // packets_out 增长到新高点时同步推进 snd_cwnd_used + snd_cwnd_stamp。
        sock.onDataSentUpdateCwndUsed();

        if (startRto || (sock.timers() != null && sock.timers().writeTimerType == TimerType.TLP_PROBE)) {
            sock.sender().scheduleRetransmit();
        }
    }

    private void scheduleLossProbe(TcpSock sock) {
        if (sock == null || !sock.hasConnection() || sock.packetsOut() == 0 || sock.sndWnd() == 0) {
            return;
        }
        if (sock.sender().tlpHighSeq() != 0) {
            return;
        }
        if (sock.packetsOut() > 2) {
            return;
        }
        if (sock.timers() != null && sock.timers().writeTimerType == TimerType.ZERO_WINDOW_PROBE) {
            return;
        }
        long timeout = tlpTimeout(sock);
        if (timeout <= 0L || timeout >= sock.rtoMs()) {
            return;
        }
        sock.tlpHighSeq(sock.sndNxt());
        sock.sender().scheduleLossProbe(timeout);
    }

    /**
     * 对齐 Linux {@code tcp_schedule_loss_probe}(net/ipv4/tcp_output.c)与
     * RFC 8985 §3.2 的 PTO(probe timeout)公式:
     *
     * <pre>
     *   PTO = 2 * SRTT + WCDelAckT     if packets_out == 1   (single-segment tail)
     *   PTO = 2 * SRTT + 2 ticks       if packets_out > 1
     * </pre>
     *
     * <p>{@code WCDelAckT}(worst-case delayed ACK timer)取 Linux {@code tcp_rto_min}
     * 即 {@link TcpConstants#RTO_MIN_MS}(200ms);单段 tail 场景下 TLP 几乎被 RTO 撞掉,
     * 让 RTO 主导单段重传(因为单段没有"tail"结构,TLP 探测意义不大)。
     * 多段在飞时只加 2ms 的 timer 余量,让 TLP 比 RTO 早 ~rto-2ms 触发,完成 tail 探测。
     *
     * <p>历史上 v2 用 {@code (2*srtt) + max(ackTimeoutMs, ATO_MIN)},不区分 packets_out;
     * 在本地 loopback + 客户端 quick-ACK 场景下导致 ~56ms 频繁触发 TLP 探测,
     * Wireshark 看到 [TCP Spurious Retransmission] — 协议正常但 wire 上视觉嘈杂。
     * 本次按 Linux/RFC 严格对齐。
     *
     * <p>cap 上限按 {@code rto - 1}(对齐 Linux {@code rto_delta_us} 兜底)。
     */
    private long tlpTimeout(TcpSock sock) {
        long srttMs = sock.srttUs() > 0L
                ? Math.max(sock.srttUs() / 1_000L, 1L)
                : Math.max(sock.rtoMs() >> 1, 1L);
        long timeout;
        if (sock.srttUs() > 0L) {
            timeout = srttMs << 1;
            if (sock.packetsOut() == 1) {
                timeout += TcpConstants.RTO_MIN_MS;        // WCDelAckT
            } else {
                timeout += 2L;                              // TCP_TIMEOUT_MIN ≈ 2ms
            }
        } else {
            timeout = TcpConstants.RTO_INIT_MS;
        }
        long rto = sock.rtoMs();
        if (rto <= 1L) {
            return 1L;
        }
        return Math.max(1L, Math.min(timeout, rto - 1L));
    }

    /** @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_output.c#L566">minshallUpdate</a> */
    private void minshallUpdate(TcpSock sock, int mss_now, TcpSegment skb) {
        if (skb.dataLen() < skbPcount(skb) * mss_now) {
            sock.sndSml(skb.endSeq());
        }
    }

    /** Always 1 — no TSO/GSO. */
    private int skbPcount(TcpSegment skb) {
        return 1;
    }

    /** @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_output.c#L2745">inCwndReduction</a> */
    private boolean inCwndReduction(TcpSock sock) {
        return false;
    }

    // tcp_cwnd_validate / tcp_cwnd_application_limited / tcp_cwnd_restart 已迁移至
    // TcpSock(与 Linux 把它们放在 tcp_sock 状态机边缘一致);TcpOutput 只在
    // writeXmit 尾部调用 sock.tcpCwndValidate(is_cwnd_limited)。

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
     *   <li>{@code window_clamp / rcv_ssthresh / fullSpace / spaceFree} trade-off</li>
     *   <li>Zero-window thresholds ({@code free_space < allowed_space/16} or {@code &lt; mss})</li>
     *   <li>{@code rounddown(free_space, mss)} when {@code rcv_wscale == 0}</li>
     * </ul>
     */
    /**
     * 对齐 Linux {@code __tcp_select_window} (tcp_output.c:3084) + {@code tcp_select_window}
     * 外层 no-shrink 包装的复合实现。
     *
     * <p>算法要点:
     * <ol>
     *   <li>{@code free_space = spaceFree(sk)},{@code allowed_space = fullSpace(sk)},
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
    static int selectAdvertisedWindow(TcpSock sock) {
        final int curWin = sock.receiver().receiveWindow();
        final int wscale = sock.rcvWscale();
        int mss = sock.rcvMss();
        int freeSpace = spaceFree(sock);
        int allowedSpace = fullSpace(sock);
        int fullSpace = Math.min(sock.windowClamp(), allowedSpace);

        if (mss <= 0) {
            mss = TcpConstants.TCP_MSS_DEFAULT;
        }
        if (mss > fullSpace) {
            mss = fullSpace;
        }

        // 中间分支(对齐 Linux __tcp_select_window:free_space < full_space/2 时):
        //   1. icsk->icsk_ack.quick = 0 —— 退出 quick-ACK 模式,降低 ACK 风暴风险;
        //   2. free_space = round_down(free_space, mss) —— MSS 对齐再判定零窗;
        //   3. 若 free_space < allowed_space/16 || free_space < mss → 通告零窗。
        if (freeSpace < (fullSpace >> 1)) {
            sock.receiver().quickAckCount(0);
            if (mss > 0) {
                freeSpace = roundDown(freeSpace, mss);
            }
            if (freeSpace < (allowedSpace >> 4) || freeSpace < mss) {
                sock.receiver().rcvWnd(0);
                sock.receiver().rcvWup(sock.receiver().rcvNxt());
                return 0;
            }
        }

        // 内存压力下 clamp rcv_ssthresh 到 4*advmss — 对齐 Linux tcp_clamp_window 语义
        int effectiveRcvSsthresh = sock.rcvSsthresh();
        if (SysctlOptions.underMemoryPressure()) {
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
            window = sock.receiver().rcvWnd();
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

        // no-shrink 外层保护(对齐 Linux tcp_select_window):无论 wscale 是否为 0,
        // 新通告窗口都不得小于已通告窗口(右边沿不得回缩,RFC 793 §3.7、9293 §3.8.6)。
        // wscale=0 时 align 步长为 1,等价于直接 fall back 到 curWin;
        // wscale!=0 时按 (1<<wscale) 边界 align curWin,保证缩放后位段足够表示。
        // 注:零窗 advertise 在前面的 "free_space < fullSpace/2 && (< allowed/16 || < mss)"
        // 早返路径 return 0 已经处理,本 if 不会拦截零窗通告。
        if (window < curWin) {
            window = align(curWin, wscale == 0 ? 1 : (1 << wscale));
        }

        sock.receiver().rcvWnd(window);
        sock.receiver().rcvWup(sock.receiver().rcvNxt());

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
    private static int winFromSpace(int space) {
        if (space <= 0) return 0;
        long win = ((long) space * TcpConstants.TCP_DEFAULT_SCALING_RATIO)
                >> TcpConstants.TCP_RMEM_TO_WIN_SCALE_SHIFT;
        return win > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) win;
    }

    /**
     * {@code fullSpace(sk) = winFromSpace(sk, sk_rcvbuf)} — 当前接收缓冲区总预算
     * 转换为可通告窗口字节数。
     */
    private static int fullSpace(TcpSock sock) {
        return winFromSpace(Math.max(sock.rcvBuf(), 0));
    }

    /**
     * {@code spaceFree(sk) = winFromSpace(sk, sk_rcvbuf - sk_rmem_alloc - sk_backlog.len)}。
     * v2 的 {@code rmem_alloc}(OFO + in-order)已对齐 Linux {@code sk_rmem_alloc};
     * {@code sk_backlog.len} 未建模(v2 直接 EventLoop 派发,不存在软中断 backlog),近似忽略。
     */
    private static int spaceFree(TcpSock sock) {
        int used = (sock.hasConnection() && sock.receiver().buffer() != null)
                ? sock.receiver().buffer().rmemAlloc()
                : 0;
        return winFromSpace(Math.max(sock.rcvBuf() - used, 0));
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

    private int xmitProbeSkb(TcpSock sock, int urgent, int mib) {
        final FourTuple ft = sock.fourTuple();
        // rcvNxt 必须只读一次:旧版 eventAckSent 再读一次,导致守卫
        // `rcv_nxt != sock.receiver().rcvNxt()` 对自身恒为 false,
        // 跨 async 期间若 rcvNxt 被入站段推进,我们发出的 ACK 段携带老值,
        // 守卫本应拒绝 clearAckPending 却被旁路,错误地清零 ack-pending 位。
        final int rcvNxt = sock.receiver().rcvNxt();
        int seq = sock.sndUna() - (urgent == 0 ? 1 : 0);
        ByteBuf buf = TcpPacketBuilder.buildRaw(
                ft.dstAddrBytes(), ft.dstPort(),
                ft.srcAddrBytes(), ft.srcPort(),
                seq, rcvNxt,
                TCPHDR_ACK, selectAdvertisedWindow(sock), null, null, 0);
        sock.channel().writeAndFlush(buf);
        eventAckSent(sock, rcvNxt);
        return 0;
    }

    private void eventAckSent(TcpSock sock, int rcv_nxt) {
        if (rcv_nxt != sock.receiver().rcvNxt()) {
            return;
        }
        sock.decQuickAckMode();
        if (sock.hasAckPending(TcpConstants.ACK_SCHED | TcpConstants.ACK_TIMER)) {
            if (sock.hasAckPending(TcpConstants.ACK_TIMER)) {
                TcpTimerScheduler.INSTANCE.cancelDelayedAck(sock);
            }
            sock.receiver().clearAckPending(TcpConstants.ACK_SCHED | TcpConstants.ACK_TIMER);
        }
        sock.receiver().clearAckPending(TcpConstants.ACK_NOW);
    }
}

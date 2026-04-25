package com.github.pangolin.routing.acceptor.tun.net.v2.tcp.core;

import com.github.pangolin.routing.acceptor.tun.net.codec.TcpPacketBuf;
import com.github.pangolin.routing.acceptor.tun.net.handler.tcp.util.TcpOptionCodec;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.core.hook.TcpSockHandler;

import static com.github.pangolin.routing.acceptor.tun.net.handler.tcp.util.TcpUtils.determineEndSeq;
import static com.github.pangolin.routing.acceptor.tun.net.v2.tcp.core.SkbDropReason.SKB_DROP_REASON_TCP_ACK_UNSENT_DATA;
import static com.github.pangolin.routing.acceptor.tun.net.v2.tcp.core.SkbDropReason.SKB_DROP_REASON_TCP_TOO_OLD_ACK;
import static com.github.pangolin.routing.acceptor.tun.net.v2.tcp.core.TcpSequence.after;
import static com.github.pangolin.routing.acceptor.tun.net.v2.tcp.core.TcpSequence.before;

/**
 * 入站 ACK 处理静态入口 — 对齐 Linux {@code tcp_ack} 及 {@code tcp_sacktag_write_queue} /
 * {@code tcp_clean_rtx_queue} / {@code tcp_rack_detect_loss} 家族。
 *
 * <p>历史上本类曾以 netty {@code ChannelInboundHandler} 形式注册到 per-connection pipeline,
 * 随 v2 向 {@link com.github.pangolin.routing.acceptor.tun.net.v2.tcp.core.SegmentDispatcher}
 * 主循环集中分发演进后,handler 身份已移除,仅保留标志位常量与静态入口供主循环调用。
 */
public final class TcpAck {

    private TcpAck() {}

    public static final int FLAG_DATA = 0x01;
    public static final int FLAG_WIN_UPDATE = 0x02;
    public static final int FLAG_DATA_ACKED = 0x04;
    public static final int FLAG_RETRANS_DATA_ACKED = 0x08;
    public static final int FLAG_SYN_ACKED = 0x10;
    /** 本 ACK 通过 SACK 块新 tagged 了至少一个 RTX 段 — 对齐 Linux {@code FLAG_DATA_SACKED}。 */
    public static final int FLAG_DATA_SACKED = 0x20;
    public static final int FLAG_SLOWPATH = 0x100;
    public static final int FLAG_SND_UNA_ADVANCED = 0x400;
    public static final int FLAG_UPDATE_TS_RECENT = 0x4000;
    public static final int FLAG_NO_CHALLENGE_ACK = 0x8000;

    public static int tcpAck(TcpSock sock, TcpPacketBuf pkt, int flags) {
        final int priorSndUna = sock.sndUna();
        final int priorPktsOut = sock.packetsOut();
        final int ackSeq = pkt.tcpSeq();
        final int ack = pkt.tcpAckNum();
        sock.onSegmentReceived();
        // 对齐 Linux tcp_rate_gen 前置:本 ACK 内 rs->prior_delivered 的 scratchpad 清零,
        // clean_rtx / sacktag 路径在识别投递段时用 tx.delivered 的最大值刷新。
        sock.clearRackAckPriorDelivered();

        if (before(ack, priorSndUna)) {
            final int maxWindow = (int) Math.min(sock.maxWindow(), sock.bytesAcked());
            if (before(ack, priorSndUna - maxWindow)) {
                if ((flags & FLAG_NO_CHALLENGE_ACK) == 0) {
                    sock.sender().sendChallengeAck(false);
                }
                sock.stack().mib().incDrop(SKB_DROP_REASON_TCP_TOO_OLD_ACK);
                return -SKB_DROP_REASON_TCP_TOO_OLD_ACK;
            }
            return 0;
        }

        if (after(ack, sock.sndNxt())) {
            sock.stack().mib().incDrop(SKB_DROP_REASON_TCP_ACK_UNSENT_DATA);
            return -SKB_DROP_REASON_TCP_ACK_UNSENT_DATA;
        }

        if (after(ack, priorSndUna)) {
            flags |= FLAG_SND_UNA_ADVANCED;
        }

        if ((flags & FLAG_UPDATE_TS_RECENT) != 0) {
            int tsval = parseTimestampValue(pkt);
            if (tsval >= 0) {
                sock.updateRecentTimestamp(tsval);
            }
        }

        if ((flags & (FLAG_SLOWPATH | FLAG_SND_UNA_ADVANCED)) == FLAG_SND_UNA_ADVANCED) {
            sock.sndWl1(ackSeq);
            flags |= FLAG_WIN_UPDATE;
        } else {
            if (ackSeq != determineEndSeq(pkt)) {
                flags |= FLAG_DATA;
            }
            flags |= tcpAckUpdateWindow(sock, pkt, (flags & FLAG_SND_UNA_ADVANCED) != 0);
        }

        sock.sndUnaUpdate(ack);
        sock.sender().probesOut(0);

        if (priorPktsOut == 0) {
            tcpAckProbe(sock);
            return 1;
        }

        // 先跑 sacktag 把 SACK 块映射到 RTX 段(对齐 Linux ackIncoming 顺序:
        // sacktagWriteQueue 在 cleanRtxQueue 之前执行,使得 clean 阶段
        // 能基于 SACK 位处理 sacked_out 计数)。
        flags |= sacktagWriteQueue(sock, pkt, priorSndUna);

        flags |= cleanRtxQueue(sock, priorSndUna);
        tcpProcessTlpAck(sock, ack);

        // 对齐 Linux tcp_fastretrans_alert 调用点:cleanRtxQueue 之后、
        // 拥塞控制更新之前尝试 tcp_try_undo_recovery(TSECR-based);命中则 CA_Recovery
        // 已迁至 CA_Open,后续 onAckedByCc 的自然出口分支不再触发 cwnd=ssthresh deflate。
        final int tsecr = parseTimestampEcho(pkt);
        if (sock.tcpTryUndoRecovery(tsecr)) {
            sock.stack().mib().inc(TcpMib.TCPFULLUNDO);
        } else if (sock.tcpTryUndoLoss(tsecr)) {
            // 对齐 Linux tcp_fastretrans_alert → tcp_try_undo_loss:CA_Loss 期间若收到对
            // 原始段(非 RTO 重传副本)的 ACK,TSECR 早于 retrans_stamp,判定为伪 RTO 并回滚。
            sock.stack().mib().inc(TcpMib.TCPLOSSUNDO);
        } else if (sock.tcpProcessFrto()) {
            // 对齐 Linux tcp_process_loss 中 F-RTO 分支(RFC 5682):CA_Loss 首 ACK
            // 的 sndUna 追平/越过 RTO 瞬间的 sndNxt 快照 — 原始在飞段全部被吸收,
            // RTO 属伪触发;不依赖 TSECR,与 LOSSUNDO 互补。
            sock.stack().mib().inc(TcpMib.TCPSPURIOUSRTOS);
        } else if (sock.tcpTryUndoDsack()) {
            // 对齐 Linux tcp_try_undo_dsack:当前 epoch 内所有重传副本都被 DSACK 抵消
            // (tp->undo_retrans 经 tcp_check_dsack 递减至 0),说明是伪触发,回滚 cwnd
            // 并迁 CA_Open。与 FULLUNDO/LOSSUNDO 互斥 — Linux 在 TSECR 分支命中后直接
            // 返回,此处走 else 分支保持同样语义。
            sock.stack().mib().inc(TcpMib.TCPDSACKUNDO);
        }

        if (after(sock.sndUna(), priorSndUna)) {
            sock.sender().rearmRto();
            int newlyAcked = Math.max(1, priorPktsOut - sock.packetsOut());
            sock.onAckedByCc(newlyAcked, true);
            sock.sender().resetBackoff();
        } else if (priorPktsOut > 0
                && ack == priorSndUna
                && (flags & (FLAG_DATA | FLAG_WIN_UPDATE)) == 0) {
            sock.onAckedByCc(1, false);
        }

        // 对齐 Linux tcp_rack_update_reo_wnd:在 ackIncoming 末尾步进 / 衰减
        // reo_wnd_steps,把 DSACK 观察转化为下一次 rackDetectLoss 的 reo_wnd 放宽。
        // S-3: 用本 ACK scratchpad 的 rackAckPriorDelivered(= rs->prior_delivered 最大值)
        // 作为 1-RTT 门控坐标,防止同一 RTT 内重复步进 reo_wnd_steps。
        sock.tcpRackUpdateReoWnd(sock.rackAckPriorDelivered());

        return Math.max(flags, 1);
    }

    /**
     * 对齐 Linux {@code tcp_sacktag_write_queue} (tcp_input.c:1780):把入站 ACK 携带的
     * SACK 块映射到 RTX 队列段,为落在任意 SACK 块 {@code [start, end)} 范围内的段置位
     * {@code TCPCB_SACKED_ACKED},并累加 {@code sock.sackedOut}。
     *
     * <p>v2 当前实现范围:
     * <ul>
     *   <li><b>完全覆盖</b>:段 {@code [seq, endSeq)} 完全落在某 SACK 块内时打位。</li>
     *   <li><b>部分覆盖</b>:通过 {@link #carveToBlock} 两次 {@code splitRtx}
     *       对齐左/右边界后再 tag。</li>
     *   <li><b>[start, end) 低于 {@code priorSndUna}</b>:该块已由累计 ACK 吸收,
     *       {@code tcp_clean_rtx_queue} 马上会释放对应段,此处跳过以免无谓 tag。</li>
     * </ul>
     *
     * <p>注:v2 当前尚未发送 SACK Permitted 之外的 SACK 选项,{@code TcpOutput} 的 delayed-ACK
     * 路径也未追加 SACK 块;本方法仅处理<b>入站方向</b>,用于未来 RACK / F-RTO 读取段级状态。
     *
     * @param priorSndUna 进入 {@code tcpAck} 前的 snd.una,用于判定 SACK 块是否已被累计 ACK 吸收
     * @return 若至少一个段被新 tagged,返回 {@link #FLAG_DATA_SACKED};否则 0
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_input.c#L1780">sacktagWriteQueue</a>
     */
    public static int sacktagWriteQueue(TcpSock sock, TcpPacketBuf pkt, int priorSndUna) {
        if (sock == null || !sock.sendBuffer().hasRtxPending()) {
            return 0;
        }
        int[] blocks = TcpOptionCodec.parseSackBlocks(pkt.tcpOptionsSlice());
        if (blocks == null || blocks.length < 2) {
            return 0;
        }
        // DSACK 识别 — 对齐 Linux tcp_check_dsack (tcp_input.c):
        //   Case 1/2: 首块 end 不超过 priorSndUna(累计 ACK 已吸收),明显 DSACK;
        //   Case 3  : 至少两块且首块被第二块包含(反序形态),首块是 OFO 重叠 DSACK。
        // 命中后首块不参与常规 tagging —— 它标的字节已不在 RTX 队列中。
        int sackStartIdx = 0;
        if (blocks.length >= 2) {
            final int fs = blocks[0];
            final int fe = blocks[1];
            boolean dupSack = false;
            if (!after(fe, priorSndUna)) {
                dupSack = true;
            } else if (blocks.length >= 4) {
                final int ss = blocks[2];
                final int se = blocks[3];
                if (!after(fe, se) && !before(fs, ss)) {
                    dupSack = true;
                }
            }
            if (dupSack) {
                sock.stack().mib().inc(TcpMib.TCPDSACKRECV);
                // 对齐 Linux tcp_check_dsack → tp->rack.dsack_seen = 1,供
                // tcp_rack_update_reo_wnd 下次步进 reo_wnd_steps 使用。
                sock.setRackDsackSeen(true);
                sackStartIdx = 2;

                // 对齐 Linux tcp_check_dsack 末段:
                //   if (dup_sack && tp->undo_marker && tp->undo_retrans > 0 &&
                //       !after(end_seq_0, prior_snd_una) &&
                //       after(end_seq_0, tp->undo_marker))
                //           tp->undo_retrans--;
                // Case 3 OFO DSACK 的首块 end 位于 prior_snd_una 之上,会被
                // !after(fe, priorSndUna) 守卫自然滤掉,仅 Case 1/2(首块 end 不超
                // priorSndUna 且高于 undoMarker,即这一块确实指向本 undo epoch 里的
                // 某个重传副本)才会递减。当 undo_retrans 降至 0 时,tcpAck 尾部的
                // tcpTryUndoDsack 将触发 undo。
                if (sock.undoMarker() != 0
                        && sock.undoRetrans() > 0
                        && !after(fe, priorSndUna)
                        && after(fe, sock.undoMarker())) {
                    sock.decrUndoRetrans(1);
                }
            }
        }
        int newlyTagged = 0;
        for (int b = sackStartIdx; b + 1 < blocks.length; b += 2) {
            final int start = blocks[b];
            final int end   = blocks[b + 1];
            // 块方向不合法或已被累计 ACK 吸收 — 跳过
            if (!before(start, end)) continue;
            if (!after(end, priorSndUna)) continue;

            // 本块扫描以快照为准,允许 carveToBlock 在遍历过程中对 RTX 队列做切分操作。
            TcpSegment[] snap = sock.sendBuffer().rtxSnapshot();
            for (TcpSegment skb : snap) {
                final int segStart = skb.startSeq();
                final int segEnd   = skb.endSeq();
                // 段已越过 SACK 块尾,后续段更高序,停止本块扫描
                if (!before(segStart, end)) break;
                // 段尾未达到 SACK 块头,下一段继续
                if (!after(segEnd, start)) continue;

                // 计算段与 SACK 块的交集 [iStart, iEnd):至少覆盖一个字节
                final int iStart = before(segStart, start) ? start : segStart;
                final int iEnd   = after(segEnd, end)      ? end   : segEnd;
                if (!before(iStart, iEnd)) continue;

                TcpSegment target = carveToBlock(sock, skb, iStart, iEnd);
                if (target == null) continue;

                if (!target.isSackAcked()) {
                    target.sacked(target.sacked() | com.github.pangolin.routing.acceptor.tun.net.v2.tcp.core.TcpConstants.TCPCB_SACKED_ACKED);
                    sock.incrSackedOut();
                    newlyTagged++;
                    // 对齐 Linux tcp_sacktag_one:每有一个段被新 SACK 确认,tp->delivered++;
                    // 本 ACK 的 rs->prior_delivered 用被确认段打戳时的快照刷新(取最大)。
                    sock.incrDelivered(1);
                    sock.updateRackAckPriorDelivered(target.txDelivered());
                    // RACK 单调更新:记录本次 SACK 覆盖范围内最新发送段的时戳 — 对齐 Linux
                    // tcp_rack_advance(&tp->rack, skb_mstamp, rtt_us)。
                    final long sentUs = target.sentTimeUs();
                    if (sentUs > 0L) {
                        final long rttUs = Math.max(System.nanoTime() / 1_000L - sentUs, 0L);
                        sock.updateRack(sentUs, rttUs);
                    }
                }
            }
        }
        if (newlyTagged > 0) {
            rackDetectLoss(sock);
        }
        return newlyTagged > 0 ? FLAG_DATA_SACKED : 0;
    }

    /**
     * RACK 丢失探测 — 对齐 Linux {@code tcp_rack_detect_loss} (tcp_recovery.c):
     * 本轮 SACK 覆盖刷新 {@code rack.mstamp} 后,遍历 RTX 队列,把所有
     * <b>未被 SACK、未被标 LOST、发送时间早于 {@code rack.mstamp - reo_wnd}</b>
     * 的段打上 {@code TCPCB_LOST} 并计入 {@code lost_out}。
     *
     * <p>reo_wnd 动态缩放 — 对齐 Linux {@code tcp_rack_reo_wnd}:
     * {@code reo_wnd = min((min_rtt * steps) >> 2, srtt_us >> 3)}。
     * {@code min_rtt} 由 {@link TcpSock#minRttUs()}(Windowed Filter,
     * {@link com.github.pangolin.routing.acceptor.tun.net.v2.tcp.core.WinMinMax})
     * 提供;尚未测量时(冷启动)退化为 {@code srtt/4} 作 base + {@code srtt>>3} 作 cap,
     * 保持 ACK 时钟稳态。DSACK 事件由 {@link TcpSock#tcpRackUpdateReoWnd(int)} 驱动
     * {@code steps} 升降,由本路径读回当前值。
     *
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_recovery.c">rackDetectLoss</a>
     */
    public static void rackDetectLoss(TcpSock sock) {
        if (sock == null || !sock.sendBuffer().hasRtxPending()) return;
        final long rackMstamp = sock.rackMstamp();
        if (rackMstamp == 0L) return;

        final long srttUs  = sock.srttUs();
        final int  steps   = Math.max(1, sock.rackReoWndSteps());
        final int  minRttUs = sock.minRttUs();
        final long baseUs;
        if (minRttUs < com.github.pangolin.routing.acceptor.tun.net.v2.tcp.core.TcpConstants.TCP_MIN_RTT_NO_SAMPLE) {
            // Linux: (min_rtt * steps) >> 2 — 先乘后移,避免 steps≥2 时 min_rtt<4 被截 0
            baseUs = ((long) minRttUs * steps) >>> 2;
        } else {
            // 冷启动兜底:无 min_rtt 采样时以 srtt/4 × steps 近似,cap 仍走 srtt>>3 分支
            baseUs = srttUs > 0 ? ((srttUs >>> 2) * steps) : 1_000L;
        }
        final long capUs   = srttUs > 0 ? srttUs >>> 3 : 8_000L;
        final long reoWndUs = Math.max(Math.min(baseUs, capUs), 1_000L);
        final long lossBoundary = rackMstamp - reoWndUs;

        for (TcpSegment skb : sock.sendBuffer().rtxView()) {
            if (skb.isSackAcked() || skb.isLost()) continue;
            final long sentUs = skb.sentTimeUs();
            if (sentUs <= 0L) continue;
            if (sentUs < lossBoundary) {
                skb.sacked(skb.sacked() | com.github.pangolin.routing.acceptor.tun.net.v2.tcp.core.TcpConstants.TCPCB_LOST);
                sock.incrLostOut();
            }
        }
    }

    /**
     * 对齐 Linux {@code tcp_mark_head_lost}:从 RTX 队首起把 {@code packets} 段标
     * LOST,供无 SACK 场景下 Fast Retransmit 读 LOST 位驱动重传选择。
     * v2 供 {@code NewReno} 在 dupacks == 3 时调用兜底,与 RACK 路径互不冲突。
     */
    public static void markHeadLost(TcpSock sock, int packets) {
        if (sock == null || packets <= 0 || !sock.sendBuffer().hasRtxPending()) return;
        int remaining = packets;
        for (TcpSegment skb : sock.sendBuffer().rtxView()) {
            if (remaining <= 0) break;
            if (skb.isSackAcked() || skb.isLost()) {
                continue;
            }
            skb.sacked(skb.sacked() | com.github.pangolin.routing.acceptor.tun.net.v2.tcp.core.TcpConstants.TCPCB_LOST);
            sock.incrLostOut();
            remaining--;
        }
    }

    /**
     * 将 {@code skb} 切成恰好覆盖 {@code [iStart, iEnd)} 的单段 — 对齐 Linux
     * {@code tcp_match_skb_to_sack}:
     * <ul>
     *   <li>左边界对齐 ({@code segStart < iStart}):{@code TcpSendBuffer.splitRtx} 在
     *       {@code iStart} 切段,新 tail 作为后续处理目标。</li>
     *   <li>右边界对齐 ({@code segEnd > iEnd}):对 target 再在 {@code iEnd} 切一次,
     *       target 保留 head 部分,tail 余留给 RTX 队列等待后续 ACK / SACK。</li>
     * </ul>
     * 返回切分完毕、与 SACK 交集完全匹配的段;失败返回 {@code null}。
     */
    private static TcpSegment carveToBlock(TcpSock sock, TcpSegment skb, int iStart, int iEnd) {
        TcpSegment target = skb;
        if (before(target.startSeq(), iStart)) {
            TcpSegment tail = sock.sendBuffer().splitRtx(target, iStart);
            if (tail == null) {
                return null;
            }
            target = tail;
        }
        if (after(target.endSeq(), iEnd)) {
            TcpSegment tail = sock.sendBuffer().splitRtx(target, iEnd);
            if (tail == null) {
                return null;
            }
            // target 仍为 head(起点不变,endSeq == iEnd),tail 余留队列
        }
        return target;
    }

    /**
     * 对齐 Linux {@code tcp_clean_rtx_queue} (tcp_input.c:3340):
     * 逐条出队已被 ACK 的 SKB,根据是否重传过累积 {@code FLAG_*} 位图,采样首段
     * {@code sentTimeUs} 作为 RTT,并在循环结束后喂给 RTT 估计器。
     *
     * <p>v2 侧 {@code TcpSendBuffer} 保留容器角色(A10/A11 组织差异),本方法承担
     * 原本被 {@code TcpSendBuffer.acknowledgeUpTo} 吞并的标志/采样逻辑。
     */
    public static int cleanRtxQueue(TcpSock sock, int priorSndUna) {
        int flag = 0;
        long firstAcktUs = 0;
        long lastAcktUs = 0;
        int ackedPcount = 0;
        final int ack = sock.sndUna();

        TcpSegment skb;
        int sackedDecr = 0;
        int lostDecr = 0;
        int retransDecr = 0;
        while ((skb = sock.sendBuffer().pollIfAcknowledged(ack)) != null) {
            final boolean retrans = skb.isRetransmitted();
            final long sentUs = skb.sentTimeUs();

            if (retrans) {
                flag |= FLAG_RETRANS_DATA_ACKED;
                // 对齐 Linux tcp_clean_rtx_queue:被累计 ACK 释放的段若曾经被
                // __tcp_retransmit_skb 打过 TCPCB_SACKED_RETRANS,从 retrans_out 抵消。
                retransDecr++;
            } else if (sentUs > 0) {
                // 仅非重传段参与 RTT 采样 (Karn's algorithm)
                lastAcktUs = sentUs;
                if (firstAcktUs == 0) {
                    firstAcktUs = lastAcktUs;
                }
            }

            // 先前被 sacktagWriteQueue 打过 TCPCB_SACKED_ACKED 的段,此刻被累计 ACK
            // 吞并释放 — 同步递减 sock.sackedOut,对齐 Linux cleanRtxQueue 中
            // sacked_out -= skbPcount(skb) 的分支。同时若此前未经 SACK tagged 过,
            // 此处属于"首次投递",仍需 tp->delivered++;若已 SACK tagged 过,sacktag
            // 侧已计入,不再重复。
            if (skb.isSackAcked()) {
                sackedDecr++;
            } else {
                sock.incrDelivered(1);
            }
            // 对齐 Linux tcp_rate_skb_delivered:被累计 ACK 释放的段的 tx.delivered
            // 快照参与 rs->prior_delivered 的最大值刷新(含此前已 SACK tagged 的段)。
            sock.updateRackAckPriorDelivered(skb.txDelivered());
            // 对齐 Linux:lost_out -= skbPcount(skb) — LOST 段被累计 ACK 吸收后计数下移。
            if (skb.isLost()) {
                lostDecr++;
            }

            if (skb.isSyn()) {
                flag |= FLAG_SYN_ACKED;
            } else {
                flag |= FLAG_DATA_ACKED;
            }

            ackedPcount++;
            skb.release();
        }

        if (sackedDecr > 0) {
            sock.decrSackedOut(sackedDecr);
        }
        if (lostDecr > 0) {
            sock.decrLostOut(lostDecr);
        }
        if (retransDecr > 0) {
            sock.decrRetransOut(retransDecr);
        }

        if (ackedPcount > 0) {
            sock.decrementPacketsOut(ackedPcount);
        }

        /*
         * 通知 user-level channel:累计 ACK 释放了在途字节,sendBuffer.pendingBytes 下降,
         * TcpChannel 据此评估 writability 水位并在穿过低水位时触发 fireChannelWritabilityChanged。
         * 本路径无论 ackedPcount 是否 > 0 都通知 — 即使段不完整吞掉,SACK/LOST 计数的
         * 变化也可能影响后续可写度判定(保守)。
         */
        TcpSockHandler h = sock.handler();
        if (h != null) {
            h.onWritabilityChanged();
        }

        // 对齐 Linux cleanRtxQueue 不对 tp->undo_retrans 做递减;该字段只由
        // __tcp_retransmit_skb 在成功发出重传后自增,以及 tcp_check_dsack 观察到
        // DSACK 落入 undo_marker 窗口时自减,是否全部被 DSACK 抵消由
        // tcp_try_undo_dsack 判定(见 TcpAck.tcpAck 尾部的 TSECR/DSACK 链)。
        // retrans_stamp 在 tcpUndoCwndReduction 或下一次 tcpInitUndo 时清零,
        // 不在本路径重置,避免干扰 DSACK-driven undo 判定。

        if (firstAcktUs != 0 && (flag & FLAG_RETRANS_DATA_ACKED) == 0) {
            long nowUs = System.nanoTime() / 1_000L;
            sock.addRttSample(nowUs - firstAcktUs);
        }

        return flag;
    }

    private static void tcpProcessTlpAck(TcpSock sock, int ack) {
        int tlpHighSeq = sock.sender().tlpHighSeq();
        if (tlpHighSeq == 0) {
            return;
        }
        if (sock.packetsOut() == 0 || !before(ack, tlpHighSeq)) {
            sock.sender().tlpHighSeq(0);
        }
    }

    public static void tcpAckProbe(TcpSock sock) {
        TcpSegment head = sock.tcpSendHead();
        if (head == null) {
            return;
        }
        int wndEnd = sock.sndUna() + sock.sndWnd();
        Sender sender = sock.sender();
        if (!after(head.endSeq(), wndEnd)) {
            sender.probeBackoffShift(0);
            sender.probesTstampMs(0L);
            TcpTimerScheduler.INSTANCE.cancelWriteTimer(sock);
            return;
        }
        long when = sender.tcpProbe0WhenMs(sender.tcpRtoMaxMs());
        when = sender.tcpClampProbe0ToUserTimeout(when);
        TcpTimerScheduler.INSTANCE.scheduleWriteTimer(
                sock,
                com.github.pangolin.routing.acceptor.tun.net.v2.tcp.core.TimerType.ZERO_WINDOW_PROBE,
                when,
                sender::probeTimer
        );
    }

    /**
     * 对齐 Linux {@code tcp_ack_update_window} (tcp_input.c:3696)。
     * <p>关键差异: SYN 段 {@code window} 不应左移 wscale(未协商完成); max_window 增加时
     * 需调 {@code tcp_sync_mss} 重新夹紧 MSS(v2 无 PMTU 发现,pmtu 传 0 保持已有 MSS)。
     */
    public static int tcpAckUpdateWindow(TcpSock sock, TcpPacketBuf pkt, boolean newDataAcked) {
        int seg = pkt.tcpSeq();
        int nwin = pkt.tcpWindow();
        if (!pkt.isSyn()) {
            nwin <<= sock.sndWscale();
        }
        if (newDataAcked
                || after(seg, sock.sndWl1())
                || (seg == sock.sndWl1() && (before(sock.sndWnd(), nwin) || nwin == 0))) {
            sock.sndWl1(seg);
            if (nwin != sock.sndWnd()) {
                int priorMaxWindow = sock.maxWindow();
                sock.sndWnd(nwin);
                if (Integer.compareUnsigned(nwin, priorMaxWindow) > 0) {
                    sock.sender().syncMss(0);
                }
            }
            return FLAG_WIN_UPDATE;
        }
        return 0;
    }

    private static int parseTimestampValue(TcpPacketBuf pkt) {
        long[] ts = TcpOptionCodec.parseTimestamp(pkt.tcpOptionsSlice());
        return ts == null ? -1 : (int) ts[0];
    }

    /**
     * 解析本次 ACK 的 TSECR(对端 echo 回来的我们之前发送段 TSval);缺失返回 {@code -1}。
     * 供 {@link TcpSock#tcpTryUndoRecovery(int)} 做 TSECR-based 伪重传判定。
     */
    private static int parseTimestampEcho(TcpPacketBuf pkt) {
        long[] ts = TcpOptionCodec.parseTimestamp(pkt.tcpOptionsSlice());
        return ts == null ? -1 : (int) ts[1];
    }
}

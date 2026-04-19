package com.github.pangolin.routing.acceptor.tun.net.v2.tcp.core;

import com.github.pangolin.routing.acceptor.tun.net.handler.support.TcpPacketBuf;
import com.github.pangolin.routing.acceptor.tun.net.handler.tcp.util.TcpOptionCodec;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.connection.ConnectionKey;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.connection.TcpSendBuffer.TcpSegmentEntry;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.ng.TcpIncomingPreValidator;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.ng.TcpMultiplexer.TcpSock;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.timer.TcpTimerScheduler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import org.slf4j.Logger;

import static com.github.pangolin.routing.acceptor.tun.net.handler.tcp.util.TcpUtils.determineEndSeq;
import static com.github.pangolin.routing.acceptor.tun.net.v2.tcp.core.SkbDropReasonConstants.SKB_DROP_REASON_TCP_ACK_UNSENT_DATA;
import static com.github.pangolin.routing.acceptor.tun.net.v2.tcp.core.SkbDropReasonConstants.SKB_DROP_REASON_TCP_TOO_OLD_ACK;
import static com.github.pangolin.routing.acceptor.tun.net.v2.tcp.internal.TcpSequence.after;
import static com.github.pangolin.routing.acceptor.tun.net.v2.tcp.internal.TcpSequence.before;

public final class TcpIncomingAckHandler extends ChannelInboundHandlerAdapter {

    public enum AckResult {
        NONE,
        OLD_OR_DUP,
        NEW_DATA_ACKED
    }

    public static final ConnectionKey<AckResult> ACK_RESULT_KEY =
            ConnectionKey.of("tcp.segment-validator.ack-result");

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

    private final TcpSock sock;
    private final Logger log;
    private final TcpIncomingPreValidator incomingValidator;
    private ChannelPromise closePromise;

    public TcpIncomingAckHandler(TcpSock sock, Logger log) {
        this.sock = sock;
        this.log = log;
        this.incomingValidator = new TcpIncomingPreValidator(sock);
    }

    public void closePromise(ChannelPromise p) {
        this.closePromise = p;
        this.incomingValidator.closePromise(p);
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        if (!(msg instanceof TcpPacketBuf)) {
            ctx.fireChannelRead(msg);
            return;
        }
        TcpPacketBuf pkt = (TcpPacketBuf) msg;
        if (!incomingValidator.validate(ctx, pkt)) {
            return;
        }

        int reason = tcpAck(sock, pkt, FLAG_SLOWPATH | FLAG_UPDATE_TS_RECENT | FLAG_NO_CHALLENGE_ACK);
        if (reason <= 0) {
            if (sock.state() == com.github.pangolin.routing.acceptor.tun.net.v2.tcp.connection.TcpConnectionState.TCP_SYN_RECV) {
                return;
            }
            if (reason < 0) {
                TcpOutput.INSTANCE.tcp_send_challenge_ack(sock, false);
                return;
            }
        }
        ctx.fireChannelRead(msg);
    }

    public static int tcpAck(TcpSock sock, TcpPacketBuf pkt, int flags) {
        final int priorSndUna = sock.sndUna();
        final int priorPktsOut = sock.packetsOut();
        final int ackSeq = pkt.tcpSeq();
        final int ack = pkt.tcpAckNum();
        sock.onSegmentReceived();

        if (before(ack, priorSndUna)) {
            final int maxWindow = (int) Math.min(sock.maxWindow(), sock.bytesAcked());
            if (before(ack, priorSndUna - maxWindow)) {
                if ((flags & FLAG_NO_CHALLENGE_ACK) == 0) {
                    TcpOutput.INSTANCE.tcp_send_challenge_ack(sock, false);
                }
                TcpMibStats.INSTANCE.incDrop(SKB_DROP_REASON_TCP_TOO_OLD_ACK);
                return -SKB_DROP_REASON_TCP_TOO_OLD_ACK;
            }
            return 0;
        }

        if (after(ack, sock.sndNxt())) {
            TcpMibStats.INSTANCE.incDrop(SKB_DROP_REASON_TCP_ACK_UNSENT_DATA);
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
        sock.probesOut(0);

        if (priorPktsOut == 0) {
            tcpAckProbe(sock);
            return 1;
        }

        // 先跑 sacktag 把 SACK 块映射到 RTX 段(对齐 Linux tcp_ack 顺序:
        // tcp_sacktag_write_queue 在 tcp_clean_rtx_queue 之前执行,使得 clean 阶段
        // 能基于 SACK 位处理 sacked_out 计数)。
        flags |= tcp_sacktag_write_queue(sock, pkt, priorSndUna);

        flags |= tcp_clean_rtx_queue(sock, priorSndUna);
        tcpProcessTlpAck(sock, ack);

        if (after(sock.sndUna(), priorSndUna)) {
            TcpRetransmitter.INSTANCE.rearmRto(sock);
            int newlyAcked = Math.max(1, priorPktsOut - sock.packetsOut());
            sock.onAckedByCc(newlyAcked, true);
            sock.resetRtoBackoff();
        } else if (priorPktsOut > 0
                && ack == priorSndUna
                && (flags & (FLAG_DATA | FLAG_WIN_UPDATE)) == 0) {
            sock.onAckedByCc(1, false);
        }

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
     *   <li><b>部分覆盖</b>:暂不做 {@code tcp_fragment} 切分 — 保守跳过。Linux 会拆段
     *       以精确记账,v2 首版容忍该段稍后可能被误重传(不破坏正确性)。</li>
     *   <li><b>[start, end) 低于 {@code priorSndUna}</b>:该块已由累计 ACK 吸收,
     *       {@code tcp_clean_rtx_queue} 马上会释放对应段,此处跳过以免无谓 tag。</li>
     * </ul>
     *
     * <p>注:v2 当前尚未发送 SACK Permitted 之外的 SACK 选项,{@code TcpOutput} 的 delayed-ACK
     * 路径也未追加 SACK 块;本方法仅处理<b>入站方向</b>,用于未来 RACK / F-RTO 读取段级状态。
     *
     * @param priorSndUna 进入 {@code tcpAck} 前的 snd.una,用于判定 SACK 块是否已被累计 ACK 吸收
     * @return 若至少一个段被新 tagged,返回 {@link #FLAG_DATA_SACKED};否则 0
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_input.c#L1780">tcp_sacktag_write_queue</a>
     */
    public static int tcp_sacktag_write_queue(TcpSock sock, TcpPacketBuf pkt, int priorSndUna) {
        if (sock == null || !sock.sendBuffer().hasRtxPending()) {
            return 0;
        }
        int[] blocks = TcpOptionCodec.parseSackBlocks(pkt.tcpOptionsSlice());
        if (blocks == null || blocks.length < 2) {
            return 0;
        }
        int newlyTagged = 0;
        for (int b = 0; b + 1 < blocks.length; b += 2) {
            final int start = blocks[b];
            final int end   = blocks[b + 1];
            // 块方向不合法或已被累计 ACK 吸收 — 跳过
            if (!before(start, end)) continue;
            if (!after(end, priorSndUna)) continue;

            // 本块扫描以快照为准,允许 carveToBlock 在遍历过程中对 RTX 队列做切分操作。
            TcpSegmentEntry[] snap = sock.sendBuffer().rtxSnapshot();
            for (TcpSegmentEntry skb : snap) {
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

                TcpSegmentEntry target = carveToBlock(sock, skb, iStart, iEnd);
                if (target == null) continue;

                if (!target.isSackAcked()) {
                    target.sacked(target.sacked() | com.github.pangolin.routing.acceptor.tun.net.v2.tcp.internal.TcpConstants.TCPCB_SACKED_ACKED);
                    sock.incrSackedOut();
                    newlyTagged++;
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
            tcp_rack_detect_loss(sock);
        }
        return newlyTagged > 0 ? FLAG_DATA_SACKED : 0;
    }

    /**
     * RACK 丢失探测 — 对齐 Linux {@code tcp_rack_detect_loss} (tcp_recovery.c):
     * 本轮 SACK 覆盖刷新 {@code rack.mstamp} 后,遍历 RTX 队列,把所有
     * <b>未被 SACK、未被标 LOST、发送时间早于 {@code rack.mstamp - reo_wnd}</b>
     * 的段打上 {@code TCPCB_LOST} 并计入 {@code lost_out}。
     *
     * <p>v2 首版 reo_wnd 使用 max(srtt/4, 1ms),未引入 Linux 的动态 reo_wnd_steps 升降。
     * 影响:严格的 reo_wnd 可能对轻度乱序误判,保守起见取 RTT/4。
     *
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_recovery.c">tcp_rack_detect_loss</a>
     */
    public static void tcp_rack_detect_loss(TcpSock sock) {
        if (sock == null || !sock.sendBuffer().hasRtxPending()) return;
        final long rackMstamp = sock.rackMstamp();
        if (rackMstamp == 0L) return;

        final long srttUs = sock.srttUs();
        final long reoWndUs = Math.max(srttUs > 0 ? srttUs >>> 2 : 1_000L, 1_000L);
        final long lossBoundary = rackMstamp - reoWndUs;

        for (TcpSegmentEntry skb : sock.sendBuffer().rtxView()) {
            if (skb.isSackAcked() || skb.isLost()) continue;
            final long sentUs = skb.sentTimeUs();
            if (sentUs <= 0L) continue;
            if (sentUs < lossBoundary) {
                skb.sacked(skb.sacked() | com.github.pangolin.routing.acceptor.tun.net.v2.tcp.internal.TcpConstants.TCPCB_LOST);
                sock.incrLostOut();
            }
        }
    }

    /**
     * 对齐 Linux {@code tcp_mark_head_lost}:从 RTX 队首起把 {@code packets} 段标
     * LOST,供无 SACK 场景下 Fast Retransmit 读 LOST 位驱动重传选择。
     * v2 供 {@code NewReno} 在 dupacks == 3 时调用兜底,与 RACK 路径互不冲突。
     */
    public static void tcp_mark_head_lost(TcpSock sock, int packets) {
        if (sock == null || packets <= 0 || !sock.sendBuffer().hasRtxPending()) return;
        int remaining = packets;
        for (TcpSegmentEntry skb : sock.sendBuffer().rtxView()) {
            if (remaining <= 0) break;
            if (skb.isSackAcked() || skb.isLost()) {
                continue;
            }
            skb.sacked(skb.sacked() | com.github.pangolin.routing.acceptor.tun.net.v2.tcp.internal.TcpConstants.TCPCB_LOST);
            sock.incrLostOut();
            remaining--;
        }
    }

    /**
     * 将 {@code skb} 切成恰好覆盖 {@code [iStart, iEnd)} 的单段 — 对齐 Linux
     * {@code tcp_match_skb_to_sack}:
     * <ul>
     *   <li>左边界对齐 ({@code segStart < iStart}):{@link TcpSendBuffer#splitRtx} 在
     *       {@code iStart} 切段,新 tail 作为后续处理目标。</li>
     *   <li>右边界对齐 ({@code segEnd > iEnd}):对 target 再在 {@code iEnd} 切一次,
     *       target 保留 head 部分,tail 余留给 RTX 队列等待后续 ACK / SACK。</li>
     * </ul>
     * 返回切分完毕、与 SACK 交集完全匹配的段;失败返回 {@code null}。
     */
    private static TcpSegmentEntry carveToBlock(TcpSock sock, TcpSegmentEntry skb, int iStart, int iEnd) {
        TcpSegmentEntry target = skb;
        if (before(target.startSeq(), iStart)) {
            TcpSegmentEntry tail = sock.sendBuffer().splitRtx(target, iStart);
            if (tail == null) {
                return null;
            }
            target = tail;
        }
        if (after(target.endSeq(), iEnd)) {
            TcpSegmentEntry tail = sock.sendBuffer().splitRtx(target, iEnd);
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
    public static int tcp_clean_rtx_queue(TcpSock sock, int priorSndUna) {
        int flag = 0;
        long firstAcktUs = 0;
        long lastAcktUs = 0;
        int ackedPcount = 0;
        final int ack = sock.sndUna();

        TcpSegmentEntry skb;
        int ackedRetrans = 0;
        int sackedDecr = 0;
        int lostDecr = 0;
        while ((skb = sock.sendBuffer().pollIfAcknowledged(ack)) != null) {
            final boolean retrans = skb.isRetransmitted();
            final long sentUs = skb.sentTimeUs();

            if (retrans) {
                flag |= FLAG_RETRANS_DATA_ACKED;
                ackedRetrans++;
            } else if (sentUs > 0) {
                // 仅非重传段参与 RTT 采样 (Karn's algorithm)
                lastAcktUs = sentUs;
                if (firstAcktUs == 0) {
                    firstAcktUs = lastAcktUs;
                }
            }

            // 先前被 tcp_sacktag_write_queue 打过 TCPCB_SACKED_ACKED 的段,此刻被累计 ACK
            // 吞并释放 — 同步递减 sock.sackedOut,对齐 Linux tcp_clean_rtx_queue 中
            // sacked_out -= tcp_skb_pcount(skb) 的分支。
            if (skb.isSackAcked()) {
                sackedDecr++;
            }
            // 对齐 Linux:lost_out -= tcp_skb_pcount(skb) — LOST 段被累计 ACK 吸收后计数下移。
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

        if (ackedPcount > 0) {
            sock.decrementPacketsOut(ackedPcount);
        }

        // undo_retrans / retrans_stamp 维护(对齐 Linux tcp_clean_rtx_queue 尾部):
        // 当所有已标记为 retransmitted 的段都被 ACK 覆盖后,清零 retrans_stamp。
        if (ackedRetrans > 0) {
            sock.decrUndoRetrans(ackedRetrans);
        }
        if (sock.undoRetrans() == 0 && sock.retransStamp() != 0L) {
            sock.retransStamp(0L);
        }

        if (firstAcktUs != 0 && (flag & FLAG_RETRANS_DATA_ACKED) == 0) {
            long nowUs = System.nanoTime() / 1_000L;
            sock.addRttSample(nowUs - firstAcktUs);
        }

        return flag;
    }

    private static void tcpProcessTlpAck(TcpSock sock, int ack) {
        int tlpHighSeq = sock.tlpHighSeq();
        if (tlpHighSeq == 0) {
            return;
        }
        if (sock.packetsOut() == 0 || !before(ack, tlpHighSeq)) {
            sock.tlpHighSeq(0);
        }
    }

    public static void tcpAckProbe(TcpSock sock) {
        TcpSegmentEntry head = sock.tcpSendHead();
        if (head == null) {
            return;
        }
        int wndEnd = sock.sndUna() + sock.sndWnd();
        if (!after(head.endSeq(), wndEnd)) {
            sock.probeBackoffShift(0);
            sock.probesTstampMs(0L);
            TcpTimerScheduler.INSTANCE.cancelWriteTimer(sock);
            return;
        }
        long when = sock.tcpProbe0WhenMs(sock.tcpRtoMaxMs());
        when = sock.tcpClampProbe0ToUserTimeout(when);
        TcpTimerScheduler.INSTANCE.scheduleWriteTimer(
                sock,
                com.github.pangolin.routing.acceptor.tun.net.v2.tcp.timer.TimerType.ZERO_WINDOW_PROBE,
                when,
                () -> sock.probeTimerAction().run()
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
                    TcpOutput.INSTANCE.tcp_sync_mss(sock, 0);
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
}

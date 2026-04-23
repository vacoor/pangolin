package com.github.pangolin.routing.acceptor.tun.net.v2.tcp.core;

import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.core.TcpConstants;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * TCP send buffer: holds application data waiting to be segmented and sent,
 * plus a retransmit queue of in-flight segments.
 *
 * <p>Following Linux's model, the write queue stores {@link TcpSkb} objects
 * with sequence numbers assigned at enqueue time (mirroring {@code sk_write_queue} where
 * each {@code sk_buff} carries {@code TCP_SKB_CB(skb)->seq} when added via
 * {@code tcp_queue_skb}).  The same entry object is promoted from the write queue to
 * the retransmit queue by {@code tcp_event_new_data_sent} — no new allocation occurs
 * at transmission time.
 *
 * <p>All operations must be called from the connection's assigned Worker EventLoop.
 */
public final class TcpSendBuffer {

    /**
     * Unsent application data: entries with sequence numbers already assigned.
     * Mirrors Linux {@code sk->sk_write_queue}.
     */
    private final Deque<TcpSkb> writeQueue = new ArrayDeque<>();

    /** In-flight segments awaiting ACK (retransmit queue). Mirrors Linux {@code sk->tcp_rtx_queue}. */
    private final Deque<TcpSkb> rtxQueue = new ArrayDeque<>();

    // ---- Write queue ----

    /**
     * Append an entry to the tail of the write queue.
     * Mirrors Linux {@code tcp_queue_skb}: called after seq and end_seq have been set on the entry.
     */
    public void enqueue(TcpSkb entry) {
        writeQueue.addLast(entry);
    }

    /**
     * Insert an entry at the head of the write queue.
     * Used by {@code tcp_fragment} to re-enqueue the split head fragment.
     */
    public void enqueueWriteFirst(TcpSkb entry) {
        writeQueue.addFirst(entry);
    }

    public TcpSkb peekWrite() {
        return writeQueue.peekFirst();
    }

    public TcpSkb pollWrite() {
        return writeQueue.pollFirst();
    }

    public boolean hasDataToSend() {
        return !writeQueue.isEmpty();
    }

    public int writeQueueSize() {
        return writeQueue.size();
    }

    // ---- Retransmit queue ----

    public void enqueueRtx(TcpSkb entry) {
        rtxQueue.addLast(entry);
    }

    /**
     * 将 {@code entry} 插入 RTX 队列头部 — 用于 {@code fragment(TCP_FRAG_IN_RTX_QUEUE)}
     * 完成后把 head / tail 片段按序放回队列。
     */
    public void enqueueRtxFirst(TcpSkb entry) {
        rtxQueue.addFirst(entry);
    }

    /** 弹出 RTX 队列头部(调用方负责 release)— 用于 {@code tcp_fragment} 前取出待切分段。 */
    public TcpSkb pollRtx() {
        return rtxQueue.pollFirst();
    }

    public TcpSkb peekRtx() {
        return rtxQueue.peekFirst();
    }

    /**
     * Remove all RTX entries with {@code endSeq <= ackSeq} (acknowledged segments).
     *
     * @return number of entries removed
     */
    public int acknowledgeUpTo(int ackSeq) {
        int removed = 0;
        while (!rtxQueue.isEmpty()) {
            TcpSkb head = rtxQueue.peekFirst();
            if (head.endSeq() - ackSeq <= 0) {
                rtxQueue.pollFirst().release();
                removed++;
            } else {
                break;
            }
        }
        return removed;
    }

    /**
     * Poll the head entry if it is fully acknowledged by {@code ackSeq} without
     * releasing it — caller owns release, mirroring Linux {@code tcp_clean_rtx_queue}
     * drain loop that inspects {@code TCP_SKB_CB(skb)->sacked} / flags before freeing.
     *
     * @param ackSeq cumulative ACK number
     * @return polled entry (caller must call {@link TcpSkb#release()}) or {@code null}
     */
    public TcpSkb pollIfAcknowledged(int ackSeq) {
        TcpSkb head = rtxQueue.peekFirst();
        if (head == null || head.endSeq() - ackSeq > 0) {
            return null;
        }
        return rtxQueue.pollFirst();
    }

    public boolean hasRtxPending() {
        return !rtxQueue.isEmpty();
    }

    public int rtxQueueSize() {
        return rtxQueue.size();
    }

    /**
     * 只读 RTX 队列视图,供 {@code tcp_sacktag_write_queue} / 重传扫描等路径按序遍历。
     * 返回的 {@code Iterable} 背后是私有 {@link #rtxQueue};消费方不得通过迭代器修改队列。
     */
    public Iterable<TcpSkb> rtxView() {
        return rtxQueue;
    }

    /**
     * RTX 队列按引用顺序的快照,供 SACK / RACK 等路径在需要对队列做结构性变动
     * (如 {@link #splitRtx}) 的同时安全遍历。迭代以快照为准,当前帧只处理快照期间
     * 已存在的段;后续帧按需重新取快照即可。
     */
    public TcpSkb[] rtxSnapshot() {
        return rtxQueue.toArray(new TcpSkb[0]);
    }

    /**
     * 就地把 {@code target} 在 {@code splitSeq} 处切成 head / tail 两段,并替换原段 —
     * 对齐 Linux {@code fragment(TCP_FRAG_IN_RTX_QUEUE)} 的 per-skb 替换语义。
     *
     * <p>适用任意偏移切分(不要求 MSS 边界),供 SACK 局部重叠精确记账使用:
     * <ul>
     *   <li>{@code splitSeq} 必须严格落在 target 内部({@code startSeq < splitSeq < endSeq}),
     *       否则返回 {@code null} 且不改动队列。</li>
     *   <li>head 保留除 FIN / PSH 外的 flags(对齐 Linux:FIN / PSH 只能落在最后一段);
     *       tail 保留完整 flags;{@code sacked} / {@code sentTimeUs} 二者均继承自原段。</li>
     *   <li>原段的 payload 由新 head / tail 的 {@code retainedSlice} 继承引用计数,
     *       原 {@link TcpSkb} 释放。</li>
     *   <li>返回新 tail(起点 {@code splitSeq}),便于调用方继续在 tail 上做进一步切分。</li>
     * </ul>
     *
     * <p>调用方必须保证 target 当前仍在 rtxQueue 中,且不持有在本次调用后仍需使用的
     * 老 target 引用(老段会被 release)。
     */
    public TcpSkb splitRtx(TcpSkb target, int splitSeq) {
        if (target == null) return null;
        int offset = splitSeq - target.startSeq();
        if (offset <= 0 || offset >= target.dataLen()) {
            return null;
        }

        ByteBuf orig = target.payload();
        int reader = orig.readerIndex();
        ByteBuf headBuf = orig.retainedSlice(reader, offset);
        int tailLen = target.dataLen() - offset;
        ByteBuf tailBuf = orig.retainedSlice(reader + offset, tailLen);

        byte headFlags = (byte) (target.tcpFlags() & ~(TcpConstants.TCPHDR_FIN | TcpConstants.TCPHDR_PSH));
        byte tailFlags = target.tcpFlags();

        TcpSkb head = new TcpSkb(
                headBuf, target.startSeq(), offset, headFlags,
                target.sacked(), target.sentTimeUs());
        TcpSkb tail = new TcpSkb(
                tailBuf, target.startSeq() + offset, tailLen, tailFlags,
                target.sacked(), target.sentTimeUs());
        // 发送时 tp->delivered 快照随 split 一同继承 — 拆分后两半的"发送时已投递段数"
        // 保持不变,对齐 Linux fragment 保留 TCP_SKB_CB(skb)->tx 的语义。
        head.txDelivered(target.txDelivered());
        tail.txDelivered(target.txDelivered());

        // O(n) 原地替换:rtxQueue 为 ArrayDeque,iterator 不支持 remove + insertAfter。
        // SACK 块 ≤ 4、RTX 段数量有限,量级可接受。
        TcpSkb[] snap = rtxQueue.toArray(new TcpSkb[0]);
        rtxQueue.clear();
        boolean replaced = false;
        for (TcpSkb e : snap) {
            if (!replaced && e == target) {
                rtxQueue.addLast(head);
                rtxQueue.addLast(tail);
                replaced = true;
            } else {
                rtxQueue.addLast(e);
            }
        }
        if (!replaced) {
            // target 已不在队列 — 回滚切片,不变更队列
            headBuf.release();
            tailBuf.release();
            return null;
        }
        target.release();
        return tail;
    }

    /**
     * {@link #collapseRtx} 的返回值 — 承载合并后的段与被丢弃段 {@code next} 的
     * {@code sacked} 位集,便于调用方按 {@code TCPCB_LOST / RETRANS} 等位递减对应
     * {@code packets_out / lost_out / retrans_out} 计数(对齐 Linux
     * {@code tcp_adjust_pcount})。
     */
    public static final class CollapseResult {
        public final TcpSkb merged;
        public final int droppedSacked;
        public CollapseResult(TcpSkb merged, int droppedSacked) {
            this.merged = merged;
            this.droppedSacked = droppedSacked;
        }
    }

    /**
     * 就地把 RTX 队列中 {@code head} 与紧随其后的相邻段合并成单个 ≤ {@code mssNow}
     * 的段;若相邻段不存在、序号不连续、合并后超过 {@code mssNow}、任一侧含
     * {@link TcpConstants#TCPHDR_SYN} 或已被 SACK 确认则放弃合并并返回 {@code null}。
     *
     * <p>对齐 Linux {@code tcp_collapse_retrans} (tcp_output.c:2829):重传前把相邻的
     * 小段合并成 MSS 大小以减少段开销 / ACK 噪声。与 Linux 规则对齐:
     * <ul>
     *   <li>合并后 {@code tcp_flags} 取两段并集,覆盖 FIN/PSH;SYN 不参与合并(Linux
     *       {@code tcp_skb_can_collapse} 里早已把 SYN 排除,此处显式兜底)。</li>
     *   <li>{@code sacked} 继承 head,再 OR 上 next 的
     *       {@link TcpConstants#TCPCB_EVER_RETRANS} 以保留历史重传痕迹 —
     *       对齐 Linux {@code TCP_SKB_CB(skb)->sacked |= TCP_SKB_CB(next)->sacked & TCPCB_EVER_RETRANS}。
     *       其他位集(LOST / RETRANS / SACKED_ACKED)<b>不</b>进入合并段,由调用方
     *       用返回的 {@link CollapseResult#droppedSacked} 调整对应计数。</li>
     *   <li>{@code sentTimeUs} 取两段较早者,对齐 Linux
     *       {@code tcp_skb_collapse_tstamp},保证 RACK / RTT 估计走更保守路径。</li>
     *   <li>任一段带 {@link TcpConstants#TCPCB_SACKED_ACKED} 时拒绝合并 —
     *       已 SACK 段必须独立保留,以便 {@code tcp_clean_rtx_queue} 精确记账。</li>
     * </ul>
     *
     * @param head   合并起点段(必须当前仍在 rtxQueue 首段或中段)
     * @param mssNow 当前 MSS 上限
     * @return {@link CollapseResult} 成功合并时非 {@code null};前置条件不满足返回
     *         {@code null},队列不变
     */
    public CollapseResult collapseRtx(TcpSkb head, int mssNow) {
        if (head == null || mssNow <= 0) return null;

        TcpSkb[] snap = rtxQueue.toArray(new TcpSkb[0]);
        int idx = -1;
        for (int i = 0; i < snap.length - 1; i++) {
            if (snap[i] == head) {
                idx = i;
                break;
            }
        }
        if (idx < 0) return null;

        TcpSkb next = snap[idx + 1];
        if (next == null) return null;

        if (head.endSeq() != next.startSeq()) return null;
        if (head.isSyn() || next.isSyn()) return null;
        if (head.isSackAcked() || next.isSackAcked()) return null;

        final int combinedLen = head.dataLen() + next.dataLen();
        if (combinedLen > mssNow || combinedLen == 0) return null;

        ByteBuf headBuf = head.payload();
        ByteBuf nextBuf = next.payload();
        ByteBuf merged = Unpooled.wrappedBuffer(
                headBuf.retainedSlice(headBuf.readerIndex(), head.dataLen()),
                nextBuf.retainedSlice(nextBuf.readerIndex(), next.dataLen()));

        final byte mergedFlags  = (byte) (head.tcpFlags() | next.tcpFlags());
        final int  mergedSacked = head.sacked()
                | (next.sacked() & TcpConstants.TCPCB_EVER_RETRANS);
        final long mergedSentUs = (head.sentTimeUs() == 0L) ? next.sentTimeUs()
                : (next.sentTimeUs() == 0L ? head.sentTimeUs()
                : Math.min(head.sentTimeUs(), next.sentTimeUs()));

        TcpSkb collapsed = new TcpSkb(
                merged, head.startSeq(), combinedLen,
                mergedFlags, mergedSacked, mergedSentUs);
        // 对齐 Linux tcp_collapse_retrans 保留 head 的 TCP_SKB_CB:merged 段的
        // tx.delivered 取 head.txDelivered(较早发送快照),这样后续被 ACK 确认时
        // priorDelivered 以更早坐标参与 1-RTT 门控,更保守。
        collapsed.txDelivered(head.txDelivered());

        final int droppedSacked = next.sacked();

        rtxQueue.clear();
        for (int i = 0; i < snap.length; i++) {
            if (i == idx) {
                rtxQueue.addLast(collapsed);
            } else if (i == idx + 1) {
                // skip next — 已合并入 collapsed
            } else {
                rtxQueue.addLast(snap[i]);
            }
        }
        head.release();
        next.release();
        return new CollapseResult(collapsed, droppedSacked);
    }

    /**
     * 已入队但尚未发送的应用字节数(累加 {@code writeQueue} 中所有 {@link TcpSkb#dataLen}。
     * 对应 Linux {@code tp->write_seq - tp->snd_nxt} 中 "未离 pipeline 的应用数据"。
     */
    public long enqueuedBytes() {
        long total = 0;
        for (TcpSkb skb : writeQueue) {
            total += skb.dataLen();
        }
        return total;
    }

    /**
     * 在途未确认字节数 — 累加 {@code rtxQueue} 中所有 {@link TcpSkb#dataLen}。
     * 对应 Linux {@code tp->snd_nxt - tp->snd_una} 去掉已 SACK 部分的近似值。
     */
    public long unackedBytes() {
        long total = 0;
        for (TcpSkb skb : rtxQueue) {
            total += skb.dataLen();
        }
        return total;
    }

    /**
     * {@link #enqueuedBytes} + {@link #unackedBytes} — 供 {@code TcpChannel} writability
     * 水位判定用。O(n) 扫两队列;一般场景段数有限,不做缓存。
     */
    public long pendingBytes() {
        return enqueuedBytes() + unackedBytes();
    }

    /** Release all buffers (called on connection close). */
    public void releaseAll() {
        for (TcpSkb entry : writeQueue) {
            entry.release();
        }
        writeQueue.clear();
        for (TcpSkb entry : rtxQueue) {
            entry.release();
        }
        rtxQueue.clear();
    }
}

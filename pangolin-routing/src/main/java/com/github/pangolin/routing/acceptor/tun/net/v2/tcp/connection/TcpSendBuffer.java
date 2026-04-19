package com.github.pangolin.routing.acceptor.tun.net.v2.tcp.connection;

import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.internal.TcpConstants;
import io.netty.buffer.ByteBuf;

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
     * 将 {@code entry} 插入 RTX 队列头部 — 用于 {@code tcp_fragment(TCP_FRAG_IN_RTX_QUEUE)}
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
     * 对齐 Linux {@code tcp_fragment(TCP_FRAG_IN_RTX_QUEUE)} 的 per-skb 替换语义。
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

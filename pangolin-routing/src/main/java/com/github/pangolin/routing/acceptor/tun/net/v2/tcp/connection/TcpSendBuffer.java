package com.github.pangolin.routing.acceptor.tun.net.v2.tcp.connection;

import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.internal.TcpConstants;
import io.netty.buffer.ByteBuf;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * TCP send buffer: holds application data waiting to be segmented and sent,
 * plus a retransmit queue of in-flight segments.
 *
 * <p>Following Linux's model, the write queue stores {@link TcpSegmentEntry} objects
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
    private final Deque<TcpSegmentEntry> writeQueue = new ArrayDeque<>();

    /** In-flight segments awaiting ACK (retransmit queue). Mirrors Linux {@code sk->tcp_rtx_queue}. */
    private final Deque<TcpSegmentEntry> rtxQueue = new ArrayDeque<>();

    // ---- Write queue ----

    /**
     * Append an entry to the tail of the write queue.
     * Mirrors Linux {@code tcp_queue_skb}: called after seq and end_seq have been set on the entry.
     */
    public void enqueue(TcpSegmentEntry entry) {
        writeQueue.addLast(entry);
    }

    /**
     * Insert an entry at the head of the write queue.
     * Used by {@code tcp_fragment} to re-enqueue the split head fragment.
     */
    public void enqueueWriteFirst(TcpSegmentEntry entry) {
        writeQueue.addFirst(entry);
    }

    public TcpSegmentEntry peekWrite() {
        return writeQueue.peekFirst();
    }

    public TcpSegmentEntry pollWrite() {
        return writeQueue.pollFirst();
    }

    public boolean hasDataToSend() {
        return !writeQueue.isEmpty();
    }

    public int writeQueueSize() {
        return writeQueue.size();
    }

    // ---- Retransmit queue ----

    public void enqueueRtx(TcpSegmentEntry entry) {
        rtxQueue.addLast(entry);
    }

    /**
     * 将 {@code entry} 插入 RTX 队列头部 — 用于 {@code tcp_fragment(TCP_FRAG_IN_RTX_QUEUE)}
     * 完成后把 head / tail 片段按序放回队列。
     */
    public void enqueueRtxFirst(TcpSegmentEntry entry) {
        rtxQueue.addFirst(entry);
    }

    /** 弹出 RTX 队列头部(调用方负责 release)— 用于 {@code tcp_fragment} 前取出待切分段。 */
    public TcpSegmentEntry pollRtx() {
        return rtxQueue.pollFirst();
    }

    public TcpSegmentEntry peekRtx() {
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
            TcpSegmentEntry head = rtxQueue.peekFirst();
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
     * @return polled entry (caller must call {@link TcpSegmentEntry#release()}) or {@code null}
     */
    public TcpSegmentEntry pollIfAcknowledged(int ackSeq) {
        TcpSegmentEntry head = rtxQueue.peekFirst();
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
    public Iterable<TcpSegmentEntry> rtxView() {
        return rtxQueue;
    }

    /**
     * RTX 队列按引用顺序的快照,供 SACK / RACK 等路径在需要对队列做结构性变动
     * (如 {@link #splitRtx}) 的同时安全遍历。迭代以快照为准,当前帧只处理快照期间
     * 已存在的段;后续帧按需重新取快照即可。
     */
    public TcpSegmentEntry[] rtxSnapshot() {
        return rtxQueue.toArray(new TcpSegmentEntry[0]);
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
     *       原 {@link TcpSegmentEntry} 释放。</li>
     *   <li>返回新 tail(起点 {@code splitSeq}),便于调用方继续在 tail 上做进一步切分。</li>
     * </ul>
     *
     * <p>调用方必须保证 target 当前仍在 rtxQueue 中,且不持有在本次调用后仍需使用的
     * 老 target 引用(老段会被 release)。
     */
    public TcpSegmentEntry splitRtx(TcpSegmentEntry target, int splitSeq) {
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

        TcpSegmentEntry head = new TcpSegmentEntry(
                headBuf, target.startSeq(), offset, headFlags,
                target.sacked(), target.sentTimeUs());
        TcpSegmentEntry tail = new TcpSegmentEntry(
                tailBuf, target.startSeq() + offset, tailLen, tailFlags,
                target.sacked(), target.sentTimeUs());

        // O(n) 原地替换:rtxQueue 为 ArrayDeque,iterator 不支持 remove + insertAfter。
        // SACK 块 ≤ 4、RTX 段数量有限,量级可接受。
        TcpSegmentEntry[] snap = rtxQueue.toArray(new TcpSegmentEntry[0]);
        rtxQueue.clear();
        boolean replaced = false;
        for (TcpSegmentEntry e : snap) {
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
        for (TcpSegmentEntry entry : writeQueue) {
            entry.release();
        }
        writeQueue.clear();
        for (TcpSegmentEntry entry : rtxQueue) {
            entry.release();
        }
        rtxQueue.clear();
    }

    // ---- Inner type ----

    /**
     * A TCP segment stored in either the write queue or the retransmit queue.
     *
     * <p>Lifecycle mirrors Linux {@code sk_buff} with {@code TCP_SKB_CB}:
     * <ol>
     *   <li>Created with {@code startSeq} assigned in {@link TcpConnection#tcp_queue_skb} —
     *       placed in the write queue.</li>
     *   <li>Transmitted by {@code TcpOutput.tcp_transmit_skb} — seq read from this entry.</li>
     *   <li>{@code sentTimeUs} stamped and entry promoted to RTX queue by
     *       {@code TcpOutput.tcp_event_new_data_sent} — no new allocation.</li>
     *   <li>Released when ACKed ({@link #acknowledgeUpTo}) or on connection close.</li>
     * </ol>
     *
     * <p>{@code tcpFlags} mirrors {@code TCP_SKB_CB(skb)->tcp_flags}: a bitmask of
     * {@link TcpConstants#TCPHDR_FIN}, {@link TcpConstants#TCPHDR_SYN},
     * {@link TcpConstants#TCPHDR_ACK}, etc.
     * Only FIN and SYN consume sequence numbers; RST is never queued here.
     */
    public static final class TcpSegmentEntry {
        private final ByteBuf payload;
        private final int     startSeq;
        private final int     dataLen;
        private final byte    tcpFlags;   // TCP_SKB_CB(skb)->tcp_flags
        /**
         * TCP_SKB_CB(skb)->sacked 位集:承载 TCPCB_SACKED_ACKED / TCPCB_SACKED_RETRANS /
         * TCPCB_LOST / TCPCB_EVER_RETRANS 等(对齐 Linux include/net/tcp.h)。
         */
        private       int     sacked;
        private       long    sentTimeUs;   // stamped at transmission time (0 while in write queue)

        public TcpSegmentEntry(ByteBuf payload, int startSeq, int dataLen,
                               byte tcpFlags, long sentTimeUs) {
            this(payload, startSeq, dataLen, tcpFlags, 0, sentTimeUs);
        }

        public TcpSegmentEntry(ByteBuf payload, int startSeq, int dataLen,
                               byte tcpFlags, int sacked, long sentTimeUs) {
            this.payload    = payload;
            this.startSeq   = startSeq;
            this.dataLen    = dataLen;
            this.tcpFlags   = tcpFlags;
            this.sacked     = sacked;
            this.sentTimeUs = sentTimeUs;
        }

        /**
         * Exclusive end sequence number of this segment.
         * Mirrors Linux: {@code TCP_SKB_CB(skb)->end_seq = seq + dataLen + syn + fin}.
         * Only FIN and SYN occupy sequence space; RST/PSH/ACK/URG do not.
         */
        public int endSeq() {
            int ctrl = ((tcpFlags & TcpConstants.TCPHDR_FIN) != 0 ? 1 : 0)
                     + ((tcpFlags & TcpConstants.TCPHDR_SYN) != 0 ? 1 : 0);
            return startSeq + dataLen + ctrl;
        }

        public int     startSeq()        { return startSeq; }
        public int     dataLen()         { return dataLen; }
        /** Raw TCP flag bits stored on this SKB (mirrors {@code TCP_SKB_CB->tcp_flags}). */
        public byte    tcpFlags()        { return tcpFlags; }
        public boolean isFin()           { return (tcpFlags & TcpConstants.TCPHDR_FIN) != 0; }
        public boolean isSyn()           { return (tcpFlags & TcpConstants.TCPHDR_SYN) != 0; }
        public ByteBuf payload()         { return payload; }
        public long    sentTimeUs()      { return sentTimeUs; }

        /** 读取 {@code TCP_SKB_CB->sacked} 位集原值。 */
        public int     sacked()          { return sacked; }
        /** 写回 {@code TCP_SKB_CB->sacked} 位集原值。 */
        public void    sacked(int s)     { this.sacked = s; }

        /** 段是否处于"当前未被 ACK 覆盖的重传"状态 — 对应 {@code TCPCB_SACKED_RETRANS}。 */
        public boolean isRetransmitted() {
            return (sacked & TcpConstants.TCPCB_SACKED_RETRANS) != 0;
        }

        /** 段是否历史上被重传过 — 对应 {@code TCPCB_EVER_RETRANS}。 */
        public boolean isEverRetransmitted() {
            return (sacked & TcpConstants.TCPCB_EVER_RETRANS) != 0;
        }

        /** 段是否被 SACK 块确认 — 对应 {@code TCPCB_SACKED_ACKED}。 */
        public boolean isSackAcked() {
            return (sacked & TcpConstants.TCPCB_SACKED_ACKED) != 0;
        }

        /** 段是否被判定丢失 — 对应 {@code TCPCB_LOST}。 */
        public boolean isLost() {
            return (sacked & TcpConstants.TCPCB_LOST) != 0;
        }

        /**
         * 标记为已重传:置位 {@code TCPCB_SACKED_RETRANS | TCPCB_EVER_RETRANS}。
         * 对齐 Linux {@code __tcp_retransmit_skb} 末尾的 {@code TCP_SKB_CB(skb)->sacked |= ...}。
         */
        public void markRetransmitted() {
            sacked |= TcpConstants.TCPCB_SACKED_RETRANS | TcpConstants.TCPCB_EVER_RETRANS;
        }

        /** 清 {@code TCPCB_SACKED_RETRANS} 位(保留 EVER_RETRANS)— 对应 F-RTO undo 路径。 */
        public void clearSackedRetrans() {
            sacked &= ~TcpConstants.TCPCB_SACKED_RETRANS;
        }

        public void updateSentTime(long us)      { this.sentTimeUs = us; }

        public void release() {
            if (payload != null) payload.release();
        }
    }
}

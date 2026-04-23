package com.github.pangolin.routing.acceptor.tun.net.v2.tcp.core;

import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.core.TcpMib;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.core.TcpMibStats;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.core.SysctlOptions;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.core.TcpConstants;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.core.TcpSequence;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.CompositeByteBuf;
import io.netty.buffer.Unpooled;

import java.util.Map;
import java.util.TreeMap;
import java.util.function.IntConsumer;

/**
 * TCP receive buffer: delivers in-order data to the application and holds
 * out-of-order (OFO) segments until the gap is filled.
 *
 * <p>Mirrors Linux {@code sk->sk_receive_queue} + {@code tp->out_of_order_queue}:
 * <ul>
 *   <li>The OFO queue is keyed by segment {@code seq} and stores {@link TcpSegment}
 *       entries — the same struct used by the send / RTX paths, mirroring Linux's
 *       shared {@code struct sk_buff} + {@code TCP_SKB_CB} across all TCP queues.
 *       {@link TcpSegment#isFin()} carries the FIN bit so that a FIN arriving ahead of
 *       its data can be replayed when the gap is filled.</li>
 *   <li>{@link #rmemAlloc()} reports OFO + in-order bytes (Linux
 *       {@code atomic_read(&sk->sk_rmem_alloc)}). The OFO budget
 *       ({@link #OFO_MAX_BYTES}) is enforced against the OFO-only slice via
 *       {@link #ofoBytes()}, mirroring Linux {@code tcp_prune_ofo_queue}.</li>
 *   <li>Each delta is also forwarded to an external {@link IntConsumer} so the
 *       multiplexer can aggregate a global {@code tcp_memory_allocated} counter
 *       for {@code tcp_under_memory_pressure} decisions.</li>
 * </ul>
 *
 * <p>All operations must be called from the connection's assigned Worker EventLoop.
 */
public final class TcpReceiveBuffer {

    /** Maximum payload bytes allowed in the OFO queue (mirrors Linux {@code sk_rcvbuf / 2} heuristic). */
    public static final int OFO_MAX_BYTES = 256 * 1024;

    /**
     * {@link #collapseOfoQueue} 单个合并段的字节上限 — 对齐 Linux
     * {@code tcp_collapse} 的 {@code SKB_WITH_OVERHEAD(pskb_may_pull)} 思路:
     * 把邻接段合并为单一 {@link TcpSegment},避免 {@link Unpooled#wrappedBuffer} 组合件
     * 层级过深;64 KiB 覆盖典型 GRO / TSO 上限。
     */
    private static final int OFO_COLLAPSE_MAX_LEN = 64 * 1024;

    /** Out-of-order segment queue: seq → SKB. */
    private final TreeMap<Integer, TcpSegment> ofoQueue = new TreeMap<>(Integer::compareUnsigned);

    /** Accumulated in-order data ready for the application. */
    private final CompositeByteBuf readBuffer;

    /** Sum of {@code payload.readableBytes()} for all OFO entries. */
    private int ofoBytes;

    /** Sum of in-order bytes sitting in {@link #readBuffer} waiting to be consumed. */
    private int inorderBytes;

    /**
     * External notifier invoked with {@code delta} (bytes) whenever the combined
     * footprint changes. Used by {@code SegmentDispatcher} to maintain a global
     * {@code tcp_memory_allocated} accumulator. {@code null} / no-op means the
     * socket is not plugged into pressure tracking.
     */
    private final IntConsumer rmemDelta;

    public TcpReceiveBuffer(io.netty.buffer.ByteBufAllocator alloc) {
        this(alloc, SysctlOptions.tcp_mem_delta);
    }

    public TcpReceiveBuffer(io.netty.buffer.ByteBufAllocator alloc, IntConsumer rmemDelta) {
        this.readBuffer = alloc.compositeBuffer();
        this.rmemDelta  = rmemDelta != null ? rmemDelta : x -> {};
    }

    /**
     * Offer an in-order (or partially overlapping) segment.  The caller must have
     * already routed pure OFO segments to {@link #offerOfo}.
     *
     * @param seq     sequence number of the first byte of {@code data}
     * @param endSeq  exclusive end sequence (includes FIN's +1 when {@code fin} is true)
     * @param rcvNxt  current RCV.NXT
     * @param data    retained payload buffer; ownership transferred to this buffer
     * @param fin     true if this segment carries a FIN within {@code [seq, endSeq)}
     * @return a result carrying the updated RCV.NXT and whether a FIN was delivered
     *         (either in-order from this segment or drained from the OFO queue)
     */
    public OfferResult offer(int seq, int endSeq, int rcvNxt, ByteBuf data, boolean fin) {
        boolean currentFin = false;
        int dsackStart = 0;
        int dsackEnd   = 0;

        if (!TcpSequence.after(endSeq, rcvNxt)) {
            // 整段已被累计 ACK 吸收 — 对齐 Linux tcp_dsack_set (Case 1 of RFC 2883)。
            // 向调用方回传 [seq, endSeq) 作为下一次 ACK 首块的 DSACK。
            dsackStart = seq;
            dsackEnd   = endSeq;
            data.release();
        } else if (TcpSequence.after(seq, rcvNxt)) {
            // Pure OFO — defensive fallback; prefer explicit {@link #offerOfo}.
            // Case 3 DSACK(若有)从 offerOfo 里回报并透传给调用方。
            OfoResult ofo = offerOfo(seq, endSeq, data, fin);
            if (ofo.hasDsack()) {
                dsackStart = ofo.dsackStart;
                dsackEnd   = ofo.dsackEnd;
            }
        } else {
            // seq <= rcvNxt < endSeq: deliver (with leading-byte trim for partial overlap).
            int skip = rcvNxt - seq;
            if (skip > 0) {
                // 对齐 Linux tcp_dsack_extend (Case 2 of RFC 2883):跨 rcv_nxt 的重叠段,
                // [seq, rcvNxt) 已被累计 ACK 吸收,作为 DSACK 块回报给发送端;
                // [rcvNxt, endSeq) 部分仍按常规 in-order 路径交付。
                dsackStart = seq;
                dsackEnd   = rcvNxt;
            }
            int len  = data.readableBytes();
            if (skip < len) {
                ByteBuf tail = skip == 0
                        ? data.retainedSlice()
                        : data.retainedSlice(data.readerIndex() + skip, len - skip);
                addInorder(tail);
            }
            data.release();
            rcvNxt    = endSeq;
            currentFin = fin;
        }

        // Drain any contiguous OFO segments now deliverable.
        DrainOutcome drain = drainOfo(rcvNxt);
        return new OfferResult(drain.rcvNxt, currentFin || drain.finDelivered, dsackStart, dsackEnd);
    }

    /**
     * 把当前 OFO 队列的块信息写入 {@code out},最多写入 {@code maxBlocks} 块。
     * 返回实际写入的块数。相邻段 {@code prev.endSeq == next.startSeq} 会被合并成一个块。
     *
     * <p>对齐 Linux {@code tp->selective_acks[]} 的生成语义:块顺序按 seq 升序
     * (v2 OFO 队列本身按 seq 排序,天然满足)。调用方若需要 "最新 SACK 块放首位"
     * 的 Linux 语义(见 {@code tcp_sack_new_ofo_skb}),可以在写入 SACK 选项前反转顺序。
     *
     * @param out       输出数组,扁平存放 {@code [start1, end1, start2, end2, ...]};
     *                  需至少长度 {@code 2 * maxBlocks}
     * @param maxBlocks 最大块数(通常 ≤ 4)
     * @return 实际写入的块数
     */
    public int computeSackBlocks(int[] out, int maxBlocks) {
        if (maxBlocks <= 0 || ofoQueue.isEmpty()) {
            return 0;
        }
        int count = 0;
        int curStart = 0;
        int curEnd   = 0;
        boolean inRun = false;
        for (Map.Entry<Integer, TcpSegment> e : ofoQueue.entrySet()) {
            TcpSegment skb = e.getValue();
            int segStart = skb.startSeq();
            int segEnd   = skb.endSeq();
            if (!inRun) {
                curStart = segStart;
                curEnd   = segEnd;
                inRun    = true;
            } else if (segStart == curEnd) {
                curEnd = segEnd;
            } else {
                if (count < maxBlocks) {
                    out[2 * count]     = curStart;
                    out[2 * count + 1] = curEnd;
                    count++;
                }
                curStart = segStart;
                curEnd   = segEnd;
                if (count >= maxBlocks) {
                    return count;
                }
            }
        }
        if (inRun && count < maxBlocks) {
            out[2 * count]     = curStart;
            out[2 * count + 1] = curEnd;
            count++;
        }
        return count;
    }

    /**
     * Queue a pure out-of-order segment (seq &gt; rcv_nxt).  Handles leading / trailing
     * overlap against existing OFO entries and enforces {@link #OFO_MAX_BYTES}.
     * Mirrors Linux {@code dataQueueOfo} (tcp_input.c:5055).
     *
     * <p>若新段与已存 OFO 段发生重叠,按 RFC 2883 Case 3 把重复区段通过
     * {@link OfoResult#dsackStart}/{@link OfoResult#dsackEnd} 回报,调用方负责
     * 把它喂入 {@code tp->duplicate_sack[0]} 放到下一次 ACK 首块通告。
     *
     * @return 入队结果 + 可选 DSACK 块;{@link OfoResult#queued} 为 {@code false}
     *         表示段被丢弃(预算耗尽或完全覆盖);DSACK 字段与是否入队无关。
     */
    public OfoResult offerOfo(int seq, int endSeq, ByteBuf data, boolean fin) {
        final int origSeq    = seq;
        final int origEndSeq = endSeq;
        int dsackStart = 0;
        int dsackEnd   = 0;

        // Empty segment guard.
        if (seq == endSeq) {
            data.release();
            return new OfoResult(false, 0, 0);
        }

        // ── OFO 队列自身预算(Linux tcp_prune_ofo_queue / sk_rcvbuf/2 启发式) ──
        if (ofoBytes >= OFO_MAX_BYTES) {
            tcpPruneOfoQueue();
            if (ofoBytes >= OFO_MAX_BYTES) {
                TcpMibStats.INSTANCE.inc(TcpMib.TCPOFODROP);
                data.release();
                return new OfoResult(false, 0, 0);
            }
        }

        // ── Trim leading overlap against predecessor ──
        Map.Entry<Integer, TcpSegment> pred = ofoQueue.lowerEntry(seq);
        if (pred != null) {
            TcpSegment prev = pred.getValue();
            if (TcpSequence.after(prev.endSeq(), seq)) {
                // 对齐 Linux dataQueueOfo 的 Case 3 DSACK:新 OFO 段的 [origSeq,
                // min(origEndSeq, prev.endSeq())) 区段已在 pred 中出现过,回报为 DSACK。
                dsackStart = origSeq;
                dsackEnd   = TcpSequence.before(origEndSeq, prev.endSeq())
                        ? origEndSeq
                        : prev.endSeq();
                if (!TcpSequence.after(endSeq, prev.endSeq())) {
                    data.release();
                    return new OfoResult(false, dsackStart, dsackEnd);
                }
                seq = prev.endSeq();
            }
        }

        // ── Evict or trim overlapping successors ──
        java.util.Iterator<Map.Entry<Integer, TcpSegment>> it =
                ofoQueue.tailMap(seq).entrySet().iterator();
        while (it.hasNext()) {
            TcpSegment succ = it.next().getValue();
            if (!TcpSequence.before(succ.startSeq(), endSeq)) {
                break;
            }
            if (!TcpSequence.before(endSeq, succ.endSeq())) {
                // successor 被新段完全覆盖 — 新段的 [succ.start, succ.end) 区段是重复数据,
                // 回报为 DSACK(RFC 2883 Case 3);最近一次覆盖写入,保持 last-wins 语义。
                dsackStart = succ.startSeq();
                dsackEnd   = succ.endSeq();
                removeOfoBytes(succ.payload().readableBytes());
                succ.release();
                it.remove();
            } else {
                // 新段尾部与 successor 头部重叠 — 重复区段是 [succ.start, endSeq);
                // 后续把 endSeq 左移到 succ.start,先捕捉 DSACK 再截断。
                dsackStart = succ.startSeq();
                dsackEnd   = endSeq;
                endSeq = succ.startSeq();
                break;
            }
        }

        // FIN survives only when the trailing edge was NOT trimmed by a successor.
        final boolean retainedFin = fin && !TcpSequence.before(endSeq, origEndSeq);

        if (!TcpSequence.before(seq, endSeq) && !retainedFin) {
            data.release();
            return new OfoResult(false, dsackStart, dsackEnd);
        }

        final int leadingTrim = seq - origSeq;              // bytes of `data` to skip
        final int payloadLen  = (endSeq - seq) - (retainedFin ? 1 : 0);

        ByteBuf slice;
        if (payloadLen > 0) {
            slice = data.retainedSlice(data.readerIndex() + leadingTrim, payloadLen);
        } else {
            slice = Unpooled.EMPTY_BUFFER;
        }
        data.release();

        // endSeq() = startSeq + dataLen + (fin ? 1 : 0),因此有 FIN 时 dataLen 需扣除 1。
        byte flags = (byte) (retainedFin ? TcpConstants.TCPHDR_FIN : 0);
        ofoQueue.put(seq, new TcpSegment(slice, seq, payloadLen, flags, 0L));
        addOfoBytes(slice.readableBytes());
        return new OfoResult(true, dsackStart, dsackEnd);
    }

    /**
     * OFO 预算裁剪驱动 — 对齐 Linux {@code tcp_prune_queue} + {@code tcp_prune_ofo_queue}
     * (tcp_input.c):
     * <ol>
     *   <li>记 {@code TCPPRUNECALLED};</li>
     *   <li>先 {@link #collapseOfoQueue} 合并 seq 相邻段,降低 TreeMap 条目数与
     *       {@code CompositeByteBuf} 片段数(结构对齐,字节预算本身不变);</li>
     *   <li>若 {@link #ofoBytes} 仍超 {@link #OFO_MAX_BYTES},从队尾(高 seq,
     *       距 RCV.NXT 最远、最难短期内投递)逐条丢弃直至回到预算之下 — 对齐
     *       Linux 在 {@code tcp_prune_ofo_queue} 里按 rb-tree 反向遍历丢弃的语义。</li>
     * </ol>
     *
     * <p>与原 "tail-drop 队列一半" 相比:
     * <ul>
     *   <li>保留尽可能多的低 seq OFO 段,它们最可能随 RCV.NXT 推进被排空;</li>
     *   <li>裁剪粒度为 "刚好回到预算",避免为极端 DoS 场景过度回收后又需要
     *       立即重新接收相同 OFO 段;</li>
     *   <li>结构上对齐 Linux {@code collapse → prune} 两段式。</li>
     * </ul>
     */
    private void tcpPruneOfoQueue() {
        TcpMibStats.INSTANCE.inc(TcpMib.TCPPRUNECALLED);
        collapseOfoQueue();
        while (ofoBytes >= OFO_MAX_BYTES && !ofoQueue.isEmpty()) {
            Map.Entry<Integer, TcpSegment> last = ofoQueue.pollLastEntry();
            removeOfoBytes(last.getValue().payload().readableBytes());
            last.getValue().release();
        }
    }

    /**
     * 合并 OFO 队列中所有 seq 相邻段 — 对齐 Linux {@code tcp_collapse_ofo_queue}
     * (tcp_input.c:4509)+ {@code tcp_collapse} (tcp_input.c:4393):
     * <ul>
     *   <li>仅合并 {@code prev.endSeq == next.startSeq} 的邻接对(seq 必须严格连续);</li>
     *   <li>合并后单段 {@code dataLen} 不超过 {@link #OFO_COLLAPSE_MAX_LEN},防止
     *       {@link Unpooled#wrappedBuffer} 组合件层级无限生长;</li>
     *   <li>带 FIN 的段不与后继合并(FIN 必为 stream 终点,后继即为越界);</li>
     *   <li>每合并一对段记 {@link TcpMib#TCPOFOMERGE} / {@link TcpMib#TCPRCVCOLLAPSED}
     *       对齐 Linux MIB 触发语义。</li>
     * </ul>
     *
     * <p>合并后的段保留 {@code prev.tcpFlags() | next.tcpFlags()} 标志位;sacked
     * 位集清零(OFO 段当前未承载 RTX sacked 语义);{@code sentTimeUs} 置 0(OFO
     * 无发送时戳)。
     *
     * <p><b>注意</b>:v2 的 {@code ofoBytes} 只统计 payload 字节,合并不会减少
     * 字节预算(与 Linux 不同:Linux 合并能释放 per-skb {@code truesize} 开销)。
     * 本方法主要用于结构对齐 + 降低条目数 / 组合件碎片。
     */
    private void collapseOfoQueue() {
        if (ofoQueue.size() < 2) {
            return;
        }
        Map.Entry<Integer, TcpSegment> cur = ofoQueue.firstEntry();
        while (cur != null) {
            TcpSegment prev = cur.getValue();
            // FIN 段必为 stream 终点,不参与前向合并
            if (prev.isFin()) {
                cur = ofoQueue.higherEntry(cur.getKey());
                continue;
            }
            Map.Entry<Integer, TcpSegment> nextEntry = ofoQueue.higherEntry(cur.getKey());
            if (nextEntry == null) {
                break;
            }
            TcpSegment next = nextEntry.getValue();
            if (prev.endSeq() != next.startSeq()
                    || prev.dataLen() + next.dataLen() > OFO_COLLAPSE_MAX_LEN) {
                cur = nextEntry;
                continue;
            }
            final int combinedLen = prev.dataLen() + next.dataLen();
            final byte mergedFlags = (byte) (prev.tcpFlags() | next.tcpFlags());
            final ByteBuf combined = Unpooled.wrappedBuffer(
                    prev.payload().retainedSlice(),
                    next.payload().retainedSlice());
            TcpSegment merged = new TcpSegment(combined, prev.startSeq(), combinedLen, mergedFlags, 0L);

            prev.release();
            next.release();
            ofoQueue.remove(next.startSeq());
            ofoQueue.put(prev.startSeq(), merged);

            TcpMibStats.INSTANCE.inc(TcpMib.TCPOFOMERGE);
            TcpMibStats.INSTANCE.inc(TcpMib.TCPRCVCOLLAPSED);

            // 合并后 cur 指针保持在新 merged 段上,继续尝试与后继合并
            cur = ofoQueue.floorEntry(merged.startSeq());
        }
    }

    /**
     * Drain contiguous OFO entries once RCV.NXT has advanced.  Mirrors Linux
     * {@code tcp_ofo_queue} (tcp_input.c:4434): for each entry whose {@code seq}
     * falls at or before the current {@code rcvNxt}, deliver its unseen tail and
     * advance {@code rcvNxt}; stop (and signal) as soon as an entry carrying FIN
     * is drained.
     */
    private DrainOutcome drainOfo(int rcvNxt) {
        boolean finDelivered = false;
        while (!ofoQueue.isEmpty()) {
            Map.Entry<Integer, TcpSegment> head = ofoQueue.firstEntry();
            TcpSegment entry = head.getValue();

            // Still out-of-order — stop.
            if (TcpSequence.after(entry.startSeq(), rcvNxt)) {
                break;
            }

            ofoQueue.pollFirstEntry();
            removeOfoBytes(entry.payload().readableBytes());

            // Pure duplicate: entirely before rcvNxt.
            if (!TcpSequence.after(entry.endSeq(), rcvNxt)) {
                entry.release();
                continue;
            }

            int trimOffset  = rcvNxt - entry.startSeq();
            int deliverLen  = entry.endSeq() - rcvNxt - (entry.isFin() ? 1 : 0);
            if (deliverLen > 0) {
                ByteBuf slice = entry.payload().retainedSlice(
                        entry.payload().readerIndex() + trimOffset, deliverLen);
                addInorder(slice);
            }
            rcvNxt = entry.endSeq();
            boolean wasFin = entry.isFin();
            entry.release();

            if (wasFin) {
                finDelivered = true;
                break;
            }
        }
        return new DrainOutcome(rcvNxt, finDelivered);
    }

    /** @return true if there is in-order data ready to read. */
    public boolean isReadable() {
        return readBuffer.isReadable();
    }

    /**
     * Consume all in-order data and return it as a single buffer.
     * Caller takes ownership and must release the returned buffer.
     *
     * <p>Copies into a fresh buffer rather than slicing the composite —
     * a {@code readRetainedSlice} here would be invalidated by the
     * subsequent {@code discardReadComponents}, which frees the pooled
     * components backing the slice.
     */
    public ByteBuf readAll() {
        if (!readBuffer.isReadable()) {
            return Unpooled.EMPTY_BUFFER;
        }
        int taken = readBuffer.readableBytes();
        ByteBuf result = readBuffer.alloc().buffer(taken, taken);
        result.writeBytes(readBuffer, taken);
        readBuffer.discardReadComponents();
        removeInorderBytes(taken);
        return result;
    }

    /** Current combined footprint (OFO + in-order) — Linux {@code sk_rmem_alloc} analogue. */
    public int rmemAlloc() {
        return ofoBytes + inorderBytes;
    }

    /** Current OFO-only footprint. */
    public int ofoBytes() {
        return ofoBytes;
    }

    /** Current in-order queue footprint (bytes sitting in {@link #readBuffer}). */
    public int inorderBytes() {
        return inorderBytes;
    }

    /** Current OFO queue depth (entry count). */
    public int ofoSize() {
        return ofoQueue.size();
    }

    /** Release all buffers (called on connection close). */
    public void releaseAll() {
        int drainBytes = ofoBytes + inorderBytes;
        readBuffer.release();
        for (TcpSegment e : ofoQueue.values()) {
            e.release();
        }
        ofoQueue.clear();
        ofoBytes     = 0;
        inorderBytes = 0;
        if (drainBytes != 0) {
            rmemDelta.accept(-drainBytes);
        }
    }

    // ---- Byte accounting ----

    private void addInorder(ByteBuf slice) {
        int n = slice.readableBytes();
        readBuffer.addComponent(true, slice);
        if (n != 0) {
            inorderBytes += n;
            rmemDelta.accept(n);
        }
    }

    private void addOfoBytes(int n) {
        if (n == 0) return;
        ofoBytes += n;
        rmemDelta.accept(n);
    }

    private void removeOfoBytes(int n) {
        if (n == 0) return;
        ofoBytes -= n;
        rmemDelta.accept(-n);
    }

    private void removeInorderBytes(int n) {
        if (n == 0) return;
        inorderBytes -= n;
        rmemDelta.accept(-n);
    }

    // ---- Nested types ----

    /**
     * Result of {@link #offer}: new RCV.NXT, whether a FIN was delivered, and an optional
     * DSACK hint for the next outgoing ACK.
     *
     * <p>{@code dsackStart == dsackEnd} 表示本次无 DSACK;否则调用方需要把
     * {@code [dsackStart, dsackEnd)} 作为下一次 ACK 的 SACK 首块通告出去
     * (对齐 Linux {@code tp->duplicate_sack[0]})。
     */
    public static final class OfferResult {
        public final int     rcvNxt;
        public final boolean finDelivered;
        public final int     dsackStart;
        public final int     dsackEnd;

        public OfferResult(int rcvNxt, boolean finDelivered) {
            this(rcvNxt, finDelivered, 0, 0);
        }

        public OfferResult(int rcvNxt, boolean finDelivered, int dsackStart, int dsackEnd) {
            this.rcvNxt       = rcvNxt;
            this.finDelivered = finDelivered;
            this.dsackStart   = dsackStart;
            this.dsackEnd     = dsackEnd;
        }

        public boolean hasDsack() {
            return dsackStart != dsackEnd;
        }
    }

    /**
     * Result of {@link #offerOfo}: 入队成功标志 + 可选 DSACK 块
     * (对齐 RFC 2883 Case 3 / Linux {@code dataQueueOfo} 的 {@code tcp_dsack_set} 调用)。
     *
     * <p>{@code queued} 与 DSACK 字段相互独立:
     * <ul>
     *   <li>段完全覆盖并被丢弃 → {@code queued = false} 但 DSACK 非空;</li>
     *   <li>段被预算耗尽丢弃 → {@code queued = false} 且 DSACK 空;</li>
     *   <li>段入队(可能经过 pred/succ 修剪)→ {@code queued = true},重叠部分记入 DSACK。</li>
     * </ul>
     */
    public static final class OfoResult {
        public final boolean queued;
        public final int     dsackStart;
        public final int     dsackEnd;

        public OfoResult(boolean queued, int dsackStart, int dsackEnd) {
            this.queued     = queued;
            this.dsackStart = dsackStart;
            this.dsackEnd   = dsackEnd;
        }

        public boolean hasDsack() {
            return dsackStart != dsackEnd;
        }
    }

    private static final class DrainOutcome {
        final int     rcvNxt;
        final boolean finDelivered;

        DrainOutcome(int rcvNxt, boolean finDelivered) {
            this.rcvNxt       = rcvNxt;
            this.finDelivered = finDelivered;
        }
    }
}

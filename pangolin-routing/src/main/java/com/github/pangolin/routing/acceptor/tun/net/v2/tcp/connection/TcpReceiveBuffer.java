package com.github.pangolin.routing.acceptor.tun.net.v2.tcp.connection;

import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.internal.SysctlOptions;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.internal.TcpConstants;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.internal.TcpSequence;
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
 *   <li>The OFO queue is keyed by segment {@code seq} and stores {@link TcpSkb}
 *       entries — the same struct used by the send / RTX paths, mirroring Linux's
 *       shared {@code struct sk_buff} + {@code TCP_SKB_CB} across all TCP queues.
 *       {@link TcpSkb#isFin()} carries the FIN bit so that a FIN arriving ahead of
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

    /** Out-of-order segment queue: seq → SKB. */
    private final TreeMap<Integer, TcpSkb> ofoQueue = new TreeMap<>(Integer::compareUnsigned);

    /** Accumulated in-order data ready for the application. */
    private final CompositeByteBuf readBuffer;

    /** Sum of {@code payload.readableBytes()} for all OFO entries. */
    private int ofoBytes;

    /** Sum of in-order bytes sitting in {@link #readBuffer} waiting to be consumed. */
    private int inorderBytes;

    /**
     * External notifier invoked with {@code delta} (bytes) whenever the combined
     * footprint changes. Used by {@code TcpMultiplexer} to maintain a global
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

        if (!TcpSequence.after(endSeq, rcvNxt)) {
            // Fully duplicate — discard (defensive; caller normally filters this out).
            data.release();
        } else if (TcpSequence.after(seq, rcvNxt)) {
            // Pure OFO — defensive fallback; prefer explicit {@link #offerOfo}.
            offerOfo(seq, endSeq, data, fin);
        } else {
            // seq <= rcvNxt < endSeq: deliver (with leading-byte trim for partial overlap).
            int skip = rcvNxt - seq;
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
        return new OfferResult(drain.rcvNxt, currentFin || drain.finDelivered);
    }

    /**
     * Queue a pure out-of-order segment (seq &gt; rcv_nxt).  Handles leading / trailing
     * overlap against existing OFO entries and enforces {@link #OFO_MAX_BYTES}.
     * Mirrors Linux {@code tcp_data_queue_ofo} (tcp_input.c:5055).
     *
     * @return {@code true} when an entry was inserted; {@code false} if dropped due to
     *         memory pressure or because the segment was fully covered
     */
    public boolean offerOfo(int seq, int endSeq, ByteBuf data, boolean fin) {
        final int origSeq    = seq;
        final int origEndSeq = endSeq;

        // Empty segment guard.
        if (seq == endSeq) {
            data.release();
            return false;
        }

        // ── OFO 队列自身预算(Linux tcp_prune_ofo_queue / sk_rcvbuf/2 启发式) ──
        if (ofoBytes >= OFO_MAX_BYTES) {
            pruneOfoQueue();
            if (ofoBytes >= OFO_MAX_BYTES) {
                data.release();
                return false;
            }
        }

        // ── Trim leading overlap against predecessor ──
        Map.Entry<Integer, TcpSkb> pred = ofoQueue.lowerEntry(seq);
        if (pred != null) {
            TcpSkb prev = pred.getValue();
            if (TcpSequence.after(prev.endSeq(), seq)) {
                if (!TcpSequence.after(endSeq, prev.endSeq())) {
                    data.release();
                    return false;
                }
                seq = prev.endSeq();
            }
        }

        // ── Evict or trim overlapping successors ──
        java.util.Iterator<Map.Entry<Integer, TcpSkb>> it =
                ofoQueue.tailMap(seq).entrySet().iterator();
        while (it.hasNext()) {
            TcpSkb succ = it.next().getValue();
            if (!TcpSequence.before(succ.startSeq(), endSeq)) {
                break;
            }
            if (!TcpSequence.before(endSeq, succ.endSeq())) {
                removeOfoBytes(succ.payload().readableBytes());
                succ.release();
                it.remove();
            } else {
                endSeq = succ.startSeq();
                break;
            }
        }

        // FIN survives only when the trailing edge was NOT trimmed by a successor.
        final boolean retainedFin = fin && !TcpSequence.before(endSeq, origEndSeq);

        if (!TcpSequence.before(seq, endSeq) && !retainedFin) {
            data.release();
            return false;
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
        ofoQueue.put(seq, new TcpSkb(slice, seq, payloadLen, flags, 0L));
        addOfoBytes(slice.readableBytes());
        return true;
    }

    /**
     * Drop up to half of the OFO queue starting from the highest seq (least useful
     * for in-order delivery). Mirrors Linux {@code tcp_prune_ofo_queue} (tcp_input.c:4489).
     */
    private void pruneOfoQueue() {
        int target = Math.max(1, ofoQueue.size() / 2);
        int pruned = 0;
        while (pruned < target && !ofoQueue.isEmpty()) {
            Map.Entry<Integer, TcpSkb> last = ofoQueue.pollLastEntry();
            removeOfoBytes(last.getValue().payload().readableBytes());
            last.getValue().release();
            pruned++;
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
            Map.Entry<Integer, TcpSkb> head = ofoQueue.firstEntry();
            TcpSkb entry = head.getValue();

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
     */
    public ByteBuf readAll() {
        if (!readBuffer.isReadable()) {
            return Unpooled.EMPTY_BUFFER;
        }
        int taken = readBuffer.readableBytes();
        ByteBuf result = readBuffer.readRetainedSlice(taken);
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
        for (TcpSkb e : ofoQueue.values()) {
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

    /** Result of {@link #offer}: new RCV.NXT plus whether a FIN was delivered. */
    public static final class OfferResult {
        public final int     rcvNxt;
        public final boolean finDelivered;

        public OfferResult(int rcvNxt, boolean finDelivered) {
            this.rcvNxt       = rcvNxt;
            this.finDelivered = finDelivered;
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

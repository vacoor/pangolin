package com.github.pangolin.routing.acceptor.tun.net.v2.tcp.connection;

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

    public boolean hasRtxPending() {
        return !rtxQueue.isEmpty();
    }

    public int rtxQueueSize() {
        return rtxQueue.size();
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
     *   <li>Created with {@code startSeq} assigned in {@link TcpConnection#queueWrite} —
     *       placed in the write queue.</li>
     *   <li>Transmitted by {@code TcpSegmenter.tcp_transmit_skb} — seq read from this entry.</li>
     *   <li>{@code sentTimeUs} stamped and entry promoted to RTX queue by
     *       {@code TcpSegmenter.tcp_event_new_data_sent} — no new allocation.</li>
     *   <li>Released when ACKed ({@link #acknowledgeUpTo}) or on connection close.</li>
     * </ol>
     */
    public static final class TcpSegmentEntry {
        private final ByteBuf payload;
        private final int     startSeq;
        private final int     dataLen;
        private final boolean fin;
        private       boolean retransmitted;
        private       long    sentTimeUs;   // stamped at transmission time (0 while in write queue)

        public TcpSegmentEntry(ByteBuf payload, int startSeq, int dataLen,
                               boolean fin, long sentTimeUs) {
            this.payload       = payload;
            this.startSeq      = startSeq;
            this.dataLen       = dataLen;
            this.fin           = fin;
            this.retransmitted = false;
            this.sentTimeUs    = sentTimeUs;
        }

        /** Exclusive end sequence number of this segment. */
        public int endSeq() {
            return startSeq + dataLen + (fin ? 1 : 0);
        }

        public int     startSeq()        { return startSeq; }
        public int     dataLen()         { return dataLen; }
        public boolean isFin()           { return fin; }
        public ByteBuf payload()         { return payload; }
        public long    sentTimeUs()      { return sentTimeUs; }
        public boolean isRetransmitted() { return retransmitted; }

        public void markRetransmitted()          { retransmitted = true; }
        public void updateSentTime(long us)      { this.sentTimeUs = us; }

        public void release() {
            if (payload != null) payload.release();
        }
    }
}

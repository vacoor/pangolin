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
        private       boolean retransmitted;
        private       long    sentTimeUs;   // stamped at transmission time (0 while in write queue)

        public TcpSegmentEntry(ByteBuf payload, int startSeq, int dataLen,
                               byte tcpFlags, long sentTimeUs) {
            this.payload       = payload;
            this.startSeq      = startSeq;
            this.dataLen       = dataLen;
            this.tcpFlags      = tcpFlags;
            this.retransmitted = false;
            this.sentTimeUs    = sentTimeUs;
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
        public boolean isRetransmitted() { return retransmitted; }

        public void markRetransmitted()          { retransmitted = true; }
        public void updateSentTime(long us)      { this.sentTimeUs = us; }

        public void release() {
            if (payload != null) payload.release();
        }
    }
}

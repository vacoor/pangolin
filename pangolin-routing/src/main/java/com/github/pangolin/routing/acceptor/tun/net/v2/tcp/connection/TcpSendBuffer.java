package com.github.pangolin.routing.acceptor.tun.net.v2.tcp.connection;

import io.netty.buffer.ByteBuf;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * TCP send buffer: holds application data waiting to be segmented and sent,
 * plus a retransmit queue of in-flight segments.
 *
 * <p>All operations must be called from the connection's assigned Worker EventLoop.
 */
public final class TcpSendBuffer {

    /** Pending application data not yet segmented/sent. */
    private final Deque<ByteBuf> writeQueue = new ArrayDeque<>();

    /** In-flight segments awaiting ACK (retransmit queue). Each entry is a full TCP payload. */
    private final Deque<TcpSegmentEntry> rtxQueue = new ArrayDeque<>();

    // ---- Write queue ----

    public void enqueue(ByteBuf data) {
        writeQueue.addLast(data);
    }

    public ByteBuf peekWrite() {
        return writeQueue.peekFirst();
    }

    public ByteBuf pollWrite() {
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

    /** Remove all RTX entries with {@code endSeq <= ackSeq} (acknowledged segments). */
    public void acknowledgeUpTo(int ackSeq) {
        while (!rtxQueue.isEmpty()) {
            TcpSegmentEntry head = rtxQueue.peekFirst();
            if (head.endSeq() - ackSeq <= 0) {
                rtxQueue.pollFirst().release();
            } else {
                break;
            }
        }
    }

    public boolean hasRtxPending() {
        return !rtxQueue.isEmpty();
    }

    public int rtxQueueSize() {
        return rtxQueue.size();
    }

    /** Release all buffers (called on connection close). */
    public void releaseAll() {
        for (ByteBuf buf : writeQueue) {
            buf.release();
        }
        writeQueue.clear();
        for (TcpSegmentEntry entry : rtxQueue) {
            entry.release();
        }
        rtxQueue.clear();
    }

    // ---- Inner type ----

    /** An in-flight TCP segment stored in the retransmit queue. */
    public static final class TcpSegmentEntry {
        private final ByteBuf payload;
        private final int     startSeq;
        private final int     dataLen;
        private final boolean fin;
        private       boolean retransmitted;
        private       long    sentTimeUs;   // send timestamp in µs (for RTT sampling)

        public TcpSegmentEntry(ByteBuf payload, int startSeq, int dataLen,
                               boolean fin, long sentTimeUs) {
            this.payload      = payload;
            this.startSeq     = startSeq;
            this.dataLen      = dataLen;
            this.fin          = fin;
            this.retransmitted = false;
            this.sentTimeUs   = sentTimeUs;
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

        public void markRetransmitted()  { retransmitted = true; }
        public void updateSentTime(long us) { this.sentTimeUs = us; }

        public void release() {
            if (payload != null) payload.release();
        }
    }
}

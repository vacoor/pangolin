package com.github.pangolin.routing.acceptor.tun.net.handler.tcp.internal;

import io.netty.buffer.ByteBuf;

/**
 * A single entry in the TCP out-of-order queue.
 * Holds a retained slice of the TCP payload so the original packet buffer
 * can be released independently.
 *
 * Mirrors the role of struct sk_buff in Linux's out_of_order_queue rbtree.
 */
public final class OfoEntry {

    /** First sequence number of this segment (inclusive). */
    public final int seq;

    /** End sequence number (exclusive): seq + payloadLen [+ 1 if FIN]. */
    public final int endSeq;

    /** Retained payload slice — caller must call release() exactly once. */
    public final ByteBuf payload;

    /** Whether this segment carries a FIN flag within [seq, endSeq). */
    public final boolean fin;

    public OfoEntry(final int seq, final int endSeq,
                    final ByteBuf payload, final boolean fin) {
        this.seq     = seq;
        this.endSeq  = endSeq;
        this.payload = payload;
        this.fin     = fin;
    }

    /** Releases the retained payload ByteBuf. Must be called exactly once. */
    public void release() {
        payload.release();
    }
}

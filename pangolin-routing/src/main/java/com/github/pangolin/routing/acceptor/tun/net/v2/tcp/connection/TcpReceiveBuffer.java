package com.github.pangolin.routing.acceptor.tun.net.v2.tcp.connection;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.CompositeByteBuf;
import io.netty.buffer.Unpooled;

import java.util.TreeMap;

/**
 * TCP receive buffer: delivers in-order data to the application and holds
 * out-of-order (OFO) segments until the gap is filled.
 *
 * <p>All operations must be called from the connection's assigned Worker EventLoop.
 */
public final class TcpReceiveBuffer {

    /** Out-of-order segment queue: seq → payload. */
    private final TreeMap<Integer, ByteBuf> ofoQueue = new TreeMap<>(Integer::compareUnsigned);

    /** Accumulated in-order data ready for the application. */
    private final CompositeByteBuf readBuffer;

    public TcpReceiveBuffer(io.netty.buffer.ByteBufAllocator alloc) {
        this.readBuffer = alloc.compositeBuffer();
    }

    /**
     * Offer a segment to the buffer.
     *
     * @param seq     sequence number of the first byte of {@code data}
     * @param rcvNxt  current RCV.NXT (next expected sequence)
     * @param data    the segment payload; ownership transferred to this buffer
     * @return the new RCV.NXT after absorbing all contiguous data (may be unchanged)
     */
    public int offer(int seq, int rcvNxt, ByteBuf data) {
        int endSeq = seq + data.readableBytes();
        if (seq == rcvNxt) {
            // In-order: deliver immediately
            readBuffer.addComponent(true, data);
            rcvNxt = endSeq;
            // Drain any buffered OFO segments that are now contiguous
            while (!ofoQueue.isEmpty()) {
                Integer nextSeq = ofoQueue.firstKey();
                if (nextSeq == rcvNxt) {
                    ByteBuf ofo = ofoQueue.pollFirstEntry().getValue();
                    rcvNxt += ofo.readableBytes();
                    readBuffer.addComponent(true, ofo);
                } else {
                    break;
                }
            }
        } else if (Integer.compareUnsigned(seq, rcvNxt) > 0) {
            // Out-of-order: buffer it (discard duplicates)
            ByteBuf existing = ofoQueue.get(seq);
            if (existing != null) {
                data.release();
            } else {
                ofoQueue.put(seq, data);
            }
        } else {
            // Fully duplicate: discard
            data.release();
        }
        return rcvNxt;
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
        ByteBuf copy = readBuffer.copy();
        readBuffer.discardReadComponents();
        return copy;
    }

    /** Release all buffers (called on connection close). */
    public void releaseAll() {
        readBuffer.release();
        for (ByteBuf buf : ofoQueue.values()) {
            buf.release();
        }
        ofoQueue.clear();
    }
}

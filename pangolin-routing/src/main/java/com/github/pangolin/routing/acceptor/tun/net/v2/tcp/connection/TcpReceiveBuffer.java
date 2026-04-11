package com.github.pangolin.routing.acceptor.tun.net.v2.tcp.connection;

import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.internal.TcpSequence;
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
            // In-order: deliver immediately, then drain any now-contiguous OFO segments
            readBuffer.addComponent(true, data);
            rcvNxt = drainOfo(endSeq);
        } else if (TcpSequence.after(seq, rcvNxt)) {
            // Out-of-order: buffer it (discard exact-sequence duplicates)
            if (!ofoQueue.containsKey(seq)) {
                ofoQueue.put(seq, data);
            } else {
                data.release();
            }
        } else if (TcpSequence.after(endSeq, rcvNxt)) {
            // Partial overlap: leading bytes already received, deliver only the new tail
            int skip = rcvNxt - seq;
            ByteBuf tail = data.retainedSlice(data.readerIndex() + skip, data.readableBytes() - skip);
            data.release();
            readBuffer.addComponent(true, tail);
            rcvNxt = drainOfo(rcvNxt + tail.readableBytes());
        } else {
            // Fully duplicate: discard
            data.release();
        }
        return rcvNxt;
    }

    /**
     * Drain contiguous out-of-order segments into {@code readBuffer} starting from {@code rcvNxt}.
     *
     * <p>Three cases per OFO entry (mirrors Linux {@code tcp_ofo_queue}):
     * <ol>
     *   <li>Still out-of-order ({@code nextSeq > rcvNxt}): stop.</li>
     *   <li>Fully duplicate ({@code endSeq <= rcvNxt}): discard and continue.</li>
     *   <li>Partial or exact overlap: deliver only the new bytes (trim the already-received
     *       prefix when {@code nextSeq < rcvNxt}).</li>
     * </ol>
     *
     * @param rcvNxt the sequence number expected next
     * @return the updated RCV.NXT after absorbing all contiguous OFO segments
     */
    private int drainOfo(int rcvNxt) {
        while (!ofoQueue.isEmpty()) {
            Integer nextSeq = ofoQueue.firstKey();

            // Still out-of-order: no more work to do
            if (TcpSequence.after(nextSeq, rcvNxt)) {
                break;
            }

            ByteBuf ofo = ofoQueue.pollFirstEntry().getValue();
            int ofoEnd = nextSeq + ofo.readableBytes();

            // Fully duplicate: entirely before rcvNxt — discard
            if (!TcpSequence.after(ofoEnd, rcvNxt)) {
                ofo.release();
                continue;
            }

            // Partial or exact overlap: trim already-received prefix (skip = 0 when exact match)
            int skip = rcvNxt - nextSeq;   // safe: signed-int wrapping handles seq wraparound
            if (skip > 0) {
                ByteBuf tail = ofo.retainedSlice(ofo.readerIndex() + skip, ofo.readableBytes() - skip);
                ofo.release();
                ofo = tail;
            }
            rcvNxt += ofo.readableBytes();
            readBuffer.addComponent(true, ofo);
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
        // readRetainedSlice advances readerIndex, so discardReadComponents() can actually
        // free the consumed backing components. copy() does NOT advance readerIndex —
        // using it would leave all components in the buffer (memory leak).
        ByteBuf result = readBuffer.readRetainedSlice(readBuffer.readableBytes());
        readBuffer.discardReadComponents();
        return result;
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

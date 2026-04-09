package com.github.pangolin.routing.acceptor.tun.net.v2.tcp.connection;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Unit tests for {@link TcpSendBuffer} — write queue and RTX queue management.
 */
public class TcpSendBufferTest {

    private TcpSendBuffer buf;

    @Before
    public void setUp() {
        buf = new TcpSendBuffer();
    }

    @After
    public void tearDown() {
        buf.releaseAll();
    }

    // ── Write queue ───────────────────────────────────────────────────────────

    @Test
    public void empty_by_default() {
        assertFalse(buf.hasDataToSend());
        assertEquals(0, buf.writeQueueSize());
    }

    @Test
    public void enqueue_and_poll() {
        ByteBuf data = Unpooled.copiedBuffer(new byte[]{1, 2, 3});
        buf.enqueue(data);

        assertTrue(buf.hasDataToSend());
        assertEquals(1, buf.writeQueueSize());

        ByteBuf polled = buf.pollWrite();
        assertSame(data, polled);
        assertFalse(buf.hasDataToSend());
        polled.release();
    }

    @Test
    public void peek_does_not_remove() {
        ByteBuf data = Unpooled.copiedBuffer(new byte[]{1});
        buf.enqueue(data);
        assertSame(data, buf.peekWrite());
        assertSame(data, buf.peekWrite());  // still there
        assertEquals(1, buf.writeQueueSize());
        // data stays in writeQueue — released by tearDown's releaseAll()
    }

    // ── RTX queue ─────────────────────────────────────────────────────────────

    @Test
    public void rtx_queue_empty_by_default() {
        assertFalse(buf.hasRtxPending());
        assertEquals(0, buf.rtxQueueSize());
    }

    @Test
    public void rtx_acknowledge_removes_entry() {
        ByteBuf payload = Unpooled.copiedBuffer(new byte[100]);
        buf.enqueueRtx(new TcpSendBuffer.TcpSegmentEntry(payload, 0, 100, false, 0));
        assertTrue(buf.hasRtxPending());

        // ACK seq=100: endSeq=100, 100-100=0 <= 0 → acknowledged
        buf.acknowledgeUpTo(100);
        assertFalse(buf.hasRtxPending());
    }

    @Test
    public void rtx_acknowledge_partial() {
        ByteBuf seg1 = Unpooled.copiedBuffer(new byte[100]);
        ByteBuf seg2 = Unpooled.copiedBuffer(new byte[100]);
        buf.enqueueRtx(new TcpSendBuffer.TcpSegmentEntry(seg1, 0,   100, false, 0));
        buf.enqueueRtx(new TcpSendBuffer.TcpSegmentEntry(seg2, 100, 100, false, 0));
        assertEquals(2, buf.rtxQueueSize());

        // ACK seq=100: first entry has endSeq=100, so it is acknowledged; second (endSeq=200) is not
        buf.acknowledgeUpTo(100);
        assertEquals(1, buf.rtxQueueSize());
        assertTrue(buf.hasRtxPending());
        // seg2 still in queue — released by tearDown via releaseAll()
    }

    @Test
    public void rtx_acknowledge_all_with_high_seq() {
        ByteBuf payload = Unpooled.copiedBuffer(new byte[100]);
        buf.enqueueRtx(new TcpSendBuffer.TcpSegmentEntry(payload, 50, 100, false, 0));
        // endSeq = 50 + 100 = 150; ACK at 200 → 150 - 200 = -50 <= 0 → acknowledged
        buf.acknowledgeUpTo(200);
        assertFalse(buf.hasRtxPending());
    }

    @Test
    public void rtx_entry_not_acknowledged_if_partial() {
        ByteBuf payload = Unpooled.copiedBuffer(new byte[100]);
        // endSeq = 0 + 100 = 100; ACK at 99 → 100 - 99 = 1 > 0 → not yet acknowledged
        buf.enqueueRtx(new TcpSendBuffer.TcpSegmentEntry(payload, 0, 100, false, 0));
        buf.acknowledgeUpTo(99);
        assertTrue(buf.hasRtxPending());
    }

    // ── TcpSegmentEntry ───────────────────────────────────────────────────────

    @Test
    public void segment_end_seq_no_fin() {
        ByteBuf payload = Unpooled.copiedBuffer(new byte[10]);
        TcpSendBuffer.TcpSegmentEntry e = new TcpSendBuffer.TcpSegmentEntry(payload, 100, 10, false, 0);
        assertEquals(110, e.endSeq());  // 100 + 10
        payload.release();
    }

    @Test
    public void segment_end_seq_with_fin() {
        ByteBuf payload = Unpooled.copiedBuffer(new byte[10]);
        TcpSendBuffer.TcpSegmentEntry e = new TcpSendBuffer.TcpSegmentEntry(payload, 100, 10, true, 0);
        assertEquals(111, e.endSeq());  // 100 + 10 + 1 (FIN)
        payload.release();
    }

    @Test
    public void segment_mark_retransmitted() {
        ByteBuf payload = Unpooled.copiedBuffer(new byte[1]);
        TcpSendBuffer.TcpSegmentEntry e = new TcpSendBuffer.TcpSegmentEntry(payload, 0, 1, false, 1000L);
        assertFalse(e.isRetransmitted());
        e.markRetransmitted();
        assertTrue(e.isRetransmitted());
        payload.release();
    }
}

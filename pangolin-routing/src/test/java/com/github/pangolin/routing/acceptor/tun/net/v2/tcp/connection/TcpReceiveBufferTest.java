package com.github.pangolin.routing.acceptor.tun.net.v2.tcp.connection;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.buffer.UnpooledByteBufAllocator;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Unit tests for {@link TcpReceiveBuffer} — in-order delivery and OFO segment handling.
 */
public class TcpReceiveBufferTest {

    private TcpReceiveBuffer rcvBuf;

    @Before
    public void setUp() {
        rcvBuf = new TcpReceiveBuffer(UnpooledByteBufAllocator.DEFAULT);
    }

    @After
    public void tearDown() {
        rcvBuf.releaseAll();
    }

    // ── In-order delivery ─────────────────────────────────────────────────────

    @Test
    public void in_order_advances_rcvNxt() {
        ByteBuf data = Unpooled.copiedBuffer(new byte[]{1, 2, 3});
        int newRcvNxt = rcvBuf.offer(100, 100, data);
        assertEquals(103, newRcvNxt);
        assertTrue(rcvBuf.isReadable());
    }

    @Test
    public void in_order_data_readable() {
        ByteBuf data = Unpooled.copiedBuffer(new byte[]{10, 20, 30});
        rcvBuf.offer(0, 0, data);

        ByteBuf result = rcvBuf.readAll();
        try {
            assertEquals(3, result.readableBytes());
            assertEquals(10, result.readByte());
            assertEquals(20, result.readByte());
            assertEquals(30, result.readByte());
        } finally {
            result.release();
        }
    }

    @Test
    public void consecutive_in_order_segments() {
        rcvBuf.offer(0,   0,   Unpooled.copiedBuffer(new byte[]{1, 2}));
        rcvBuf.offer(2,   2,   Unpooled.copiedBuffer(new byte[]{3, 4}));
        rcvBuf.offer(4,   4,   Unpooled.copiedBuffer(new byte[]{5}));

        ByteBuf result = rcvBuf.readAll();
        try {
            assertEquals(5, result.readableBytes());
        } finally {
            result.release();
        }
    }

    // ── Out-of-order (OFO) ────────────────────────────────────────────────────

    @Test
    public void ofo_does_not_advance_rcvNxt() {
        ByteBuf ofo = Unpooled.copiedBuffer(new byte[]{4, 5});
        int newRcvNxt = rcvBuf.offer(105, 100, ofo);
        assertEquals(100, newRcvNxt);   // gap: 100..104 missing
        assertFalse(rcvBuf.isReadable());
    }

    @Test
    public void ofo_drained_after_gap_filled() {
        // Out-of-order: seq=105 arrives first
        rcvBuf.offer(105, 100, Unpooled.copiedBuffer(new byte[]{4, 5}));

        // Fill the gap: seq=100 (5 bytes, covering 100..104)
        int newRcvNxt = rcvBuf.offer(100, 100, Unpooled.copiedBuffer(new byte[]{0, 1, 2, 3, 4}));

        // After filling gap, OFO at 105 should be drained: rcvNxt = 107
        assertEquals(107, newRcvNxt);
        assertTrue(rcvBuf.isReadable());

        ByteBuf result = rcvBuf.readAll();
        try {
            assertEquals(7, result.readableBytes());  // 5 in-order + 2 OFO
        } finally {
            result.release();
        }
    }

    @Test
    public void multiple_ofo_drained_in_order() {
        // Three OFO segments: 105-106, 107-108, 109
        rcvBuf.offer(105, 100, Unpooled.copiedBuffer(new byte[]{5, 6}));
        rcvBuf.offer(107, 100, Unpooled.copiedBuffer(new byte[]{7, 8}));
        rcvBuf.offer(109, 100, Unpooled.copiedBuffer(new byte[]{9}));

        // Fill gap at 100 (5 bytes → 100..104)
        int newRcvNxt = rcvBuf.offer(100, 100, Unpooled.copiedBuffer(new byte[]{0, 1, 2, 3, 4}));
        assertEquals(110, newRcvNxt);  // all 5+2+2+1=10 bytes delivered

        ByteBuf result = rcvBuf.readAll();
        try {
            assertEquals(10, result.readableBytes());
        } finally {
            result.release();
        }
    }

    // ── Duplicate handling ────────────────────────────────────────────────────

    @Test
    public void fully_duplicate_segment_discarded() {
        rcvBuf.offer(100, 100, Unpooled.copiedBuffer(new byte[]{1, 2, 3}));
        // rcvNxt = 103; offer seq=100 again (entirely < rcvNxt) → duplicate
        int newRcvNxt = rcvBuf.offer(100, 103, Unpooled.copiedBuffer(new byte[]{1, 2, 3}));
        assertEquals(103, newRcvNxt);  // no change
    }

    @Test
    public void ofo_duplicate_discarded() {
        // First OFO at seq=200
        rcvBuf.offer(200, 100, Unpooled.copiedBuffer(new byte[]{1, 2}));
        // Second OFO at same seq: should be discarded, not replaced
        rcvBuf.offer(200, 100, Unpooled.copiedBuffer(new byte[]{3, 4}));

        // Fill gap: deliver 100..199 (100 bytes)
        int newRcvNxt = rcvBuf.offer(100, 100, Unpooled.copiedBuffer(new byte[100]));
        assertEquals(202, newRcvNxt);  // 200 + 2 (original OFO), not 4

        ByteBuf result = rcvBuf.readAll();
        try {
            assertEquals(102, result.readableBytes());
        } finally {
            result.release();
        }
    }
}

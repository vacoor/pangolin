package com.github.pangolin.routing.acceptor.tun.net.v2.tcp.internal;

import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Unit tests for {@link TcpSequence} — modular 32-bit sequence number arithmetic.
 */
public class TcpSequenceTest {

    // ── after() ───────────────────────────────────────────────────────────────

    @Test
    public void after_normal_range() {
        assertTrue(TcpSequence.after(100, 50));
        assertFalse(TcpSequence.after(50, 100));
    }

    @Test
    public void after_equal_is_false() {
        assertFalse(TcpSequence.after(100, 100));
    }

    @Test
    public void after_wraparound() {
        // Wrap: Integer.MAX_VALUE + 5 overflows to negative
        int a = Integer.MAX_VALUE - 5;   // just below wrap
        int b = Integer.MAX_VALUE + 10;  // wraps to negative (MIN_VALUE + 9)
        // b is "after" a in circular sequence space
        assertTrue(TcpSequence.after(b, a));
        assertFalse(TcpSequence.after(a, b));
    }

    // ── before() ─────────────────────────────────────────────────────────────

    @Test
    public void before_normal_range() {
        assertTrue(TcpSequence.before(50, 100));
        assertFalse(TcpSequence.before(100, 50));
    }

    @Test
    public void before_equal_is_false() {
        assertFalse(TcpSequence.before(100, 100));
    }

    @Test
    public void before_wraparound() {
        int a = Integer.MAX_VALUE - 5;
        int b = Integer.MAX_VALUE + 10;  // wraps past 2^31
        assertTrue(TcpSequence.before(a, b));
    }

    // ── between() ────────────────────────────────────────────────────────────

    @Test
    public void between_inside() {
        assertTrue(TcpSequence.between(10, 20, 30));
    }

    @Test
    public void between_left_edge_inclusive() {
        assertTrue(TcpSequence.between(10, 10, 30));
    }

    @Test
    public void between_right_edge_inclusive() {
        assertTrue(TcpSequence.between(10, 30, 30));
    }

    @Test
    public void between_below_range() {
        assertFalse(TcpSequence.between(10, 5, 30));
    }

    @Test
    public void between_above_range() {
        assertFalse(TcpSequence.between(10, 35, 30));
    }

    @Test
    public void between_wraparound() {
        // Range wraps past 0: seq1 = 0xFFFFFF00 (-256), seq2 = 0xFF (255)
        // The window (511 bytes) is << 2^31, so the signed-subtraction trick still works.
        int seq1   = 0xFFFFFF00;  // -256 signed
        int seq2   = 0x000000FF;  // 255
        int inside = 0x00000010;  // 16, past the wrap-around point
        int before = 0xFFFFFEF6;  // -266, just before seq1 — outside the range

        assertTrue(TcpSequence.between(seq1, inside, seq2));
        assertFalse(TcpSequence.between(seq1, before, seq2));
    }
}

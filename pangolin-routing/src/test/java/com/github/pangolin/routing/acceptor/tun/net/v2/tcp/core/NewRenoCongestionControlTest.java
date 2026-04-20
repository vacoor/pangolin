package com.github.pangolin.routing.acceptor.tun.net.v2.tcp.core;

import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.core.TcpConnection;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.core.TcpConstants;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;

/**
 * Unit tests for {@link NewRenoCongestionControl} — RFC 5681 New Reno CC state machine.
 */
public class NewRenoCongestionControlTest {

    private EmbeddedChannel channel;
    private TcpConnection conn;
    private AtomicInteger retransmitCount;

    @Before
    public void setUp() {
        channel = new EmbeddedChannel();
        retransmitCount = new AtomicInteger(0);
        conn = TcpConnection.builder()
                .channel(channel)
                .sndUna(0)
                .sndNxt(1000)
                .rcvNxt(0)
                .congestionControl(new NewRenoCongestionControl(),
                        c -> retransmitCount.incrementAndGet())
                .build();
    }

    @After
    public void tearDown() {
        conn.close();
        channel.finishAndReleaseAll();
    }

    // ── Initial state ─────────────────────────────────────────────────────────

    @Test
    public void initial_cwnd_is_tcp_init_cwnd() {
        assertEquals(TcpConstants.TCP_INIT_CWND, conn.congestionControl().cwnd(conn));
        assertFalse(conn.congestionControl().isInRecovery(conn));
    }

    // ── Slow start ────────────────────────────────────────────────────────────

    @Test
    public void slow_start_cwnd_grows_per_ack() {
        int cwnd = conn.congestionControl().cwnd(conn);
        // ssthresh = MAX_INT initially → slow start; cwnd += ackedSegs per ACK
        conn.congestionControl().onAck(conn, 1, true);
        assertEquals(cwnd + 1, conn.congestionControl().cwnd(conn));
    }

    @Test
    public void slow_start_cwnd_grows_by_acked_segments() {
        int cwnd = conn.congestionControl().cwnd(conn);
        conn.congestionControl().onAck(conn, 3, true);
        assertEquals(cwnd + 3, conn.congestionControl().cwnd(conn));
    }

    // ── Congestion avoidance ─────────────────────────────────────────────────

    @Test
    public void ca_phase_cwnd_increments_once_per_rtt() {
        // Force ssthresh = 5 so we enter CA
        conn.congestionControl().onTimeout(conn);  // ssthresh=5, cwnd=1
        // Get back to OPEN via a new ACK (LOSS exit), then slow-start to ssthresh
        conn.congestionControl().onAck(conn, 4, true);  // cwnd = 1 + 4 = 5 (hits ssthresh)

        int cwndAtCa = conn.congestionControl().cwnd(conn);
        // In CA: caIncrCounter += 1; if >= cwnd (5), cwnd++
        // Need 5 ACKs to grow cwnd by 1
        for (int i = 0; i < cwndAtCa; i++) {
            conn.congestionControl().onAck(conn, 1, true);
        }
        assertEquals(cwndAtCa + 1, conn.congestionControl().cwnd(conn));
    }

    // ── Fast retransmit / recovery ────────────────────────────────────────────

    @Test
    public void three_dupacks_trigger_fast_retransmit() {
        for (int i = 0; i < 3; i++) {
            conn.congestionControl().onAck(conn, 1, false);  // dupACK
        }
        assertTrue(conn.congestionControl().isInRecovery(conn));
        assertEquals(1, retransmitCount.get());
    }

    @Test
    public void three_dupacks_halve_ssthresh() {
        // cwnd = 10 initially; ssthresh = max(10/2, 2) = 5
        for (int i = 0; i < 3; i++) {
            conn.congestionControl().onAck(conn, 1, false);
        }
        // After recovery exit (full ACK), cwnd = ssthresh = 5
        conn.acknowledgeUpTo(1001);  // sndUna > highSeq (1000)
        conn.congestionControl().onAck(conn, 1, true);  // full ACK exits recovery
        assertFalse(conn.congestionControl().isInRecovery(conn));
        assertEquals(5, conn.congestionControl().cwnd(conn));
    }

    @Test
    public void fewer_than_three_dupacks_do_not_enter_recovery() {
        conn.congestionControl().onAck(conn, 1, false);
        conn.congestionControl().onAck(conn, 1, false);
        assertFalse(conn.congestionControl().isInRecovery(conn));
        assertEquals(0, retransmitCount.get());
    }

    @Test
    public void extra_dupack_in_recovery_inflates_cwnd() {
        for (int i = 0; i < 3; i++) {
            conn.congestionControl().onAck(conn, 1, false);
        }
        int cwndAfterRecovery = conn.congestionControl().cwnd(conn);
        conn.congestionControl().onAck(conn, 1, false);  // 4th dupACK
        assertEquals(cwndAfterRecovery + 1, conn.congestionControl().cwnd(conn));
    }

    // ── RTO / Loss state ─────────────────────────────────────────────────────

    @Test
    public void timeout_enters_loss_and_sets_cwnd_to_one() {
        conn.congestionControl().onTimeout(conn);
        assertTrue(conn.congestionControl().isInRecovery(conn));
        assertEquals(1, conn.congestionControl().cwnd(conn));
    }

    @Test
    public void timeout_halves_ssthresh() {
        // cwnd = 10 → ssthresh = max(10/2, 2) = 5
        conn.congestionControl().onTimeout(conn);
        // Exit LOSS + slow start to verify ssthresh
        conn.congestionControl().onAck(conn, 4, true);  // cwnd = 1→5, now at ssthresh → CA
        // One more ACK in CA phase adds to caIncrCounter but doesn't grow cwnd yet
        conn.congestionControl().onAck(conn, 1, true);
        assertEquals(5, conn.congestionControl().cwnd(conn));  // still 5 (needs 5 acks in CA)
    }

    @Test
    public void loss_exit_on_first_new_ack() {
        conn.congestionControl().onTimeout(conn);
        assertEquals(1, conn.congestionControl().cwnd(conn));
        assertTrue(conn.congestionControl().isInRecovery(conn));

        // First new ACK after RTO → exit LOSS, re-enter slow start
        conn.congestionControl().onAck(conn, 1, true);
        assertFalse(conn.congestionControl().isInRecovery(conn));
        // Slow start: cwnd was 1, now cwnd += 1 = 2
        assertEquals(2, conn.congestionControl().cwnd(conn));
    }

    @Test
    public void loss_cwnd_grows_after_exit() {
        conn.congestionControl().onTimeout(conn);  // cwnd=1, LOSS
        conn.congestionControl().onAck(conn, 1, true);  // LOSS→OPEN, cwnd=2 (SS)
        conn.congestionControl().onAck(conn, 1, true);  // cwnd=3
        conn.congestionControl().onAck(conn, 1, true);  // cwnd=4
        assertEquals(4, conn.congestionControl().cwnd(conn));
    }

    // ── Reset on new ACK ─────────────────────────────────────────────────────

    @Test
    public void dupacks_reset_on_new_ack() {
        conn.congestionControl().onAck(conn, 1, false);  // 1 dupACK
        conn.congestionControl().onAck(conn, 1, false);  // 2 dupACKs
        // New data ACK before 3rd dupACK → dupacks counter resets
        conn.congestionControl().onAck(conn, 1, true);
        // 2 more dupACKs should not trigger recovery (counter was reset)
        conn.congestionControl().onAck(conn, 1, false);
        conn.congestionControl().onAck(conn, 1, false);
        assertFalse(conn.congestionControl().isInRecovery(conn));
        assertEquals(0, retransmitCount.get());
    }
}

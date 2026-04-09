package com.github.pangolin.routing.acceptor.tun.net.v2.tcp.ext.rtt;

import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.connection.TcpConnection;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.internal.TcpConstants;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Unit tests for {@link Rfc6298RttEstimator} — SRTT/RTTVAR/RTO computation and backoff.
 */
public class Rfc6298RttEstimatorTest {

    private EmbeddedChannel channel;
    private TcpConnection conn;

    @Before
    public void setUp() {
        channel = new EmbeddedChannel();
        conn = TcpConnection.builder()
                .channel(channel)
                .sndUna(0).sndNxt(0).rcvNxt(0)
                .rttEstimator(new Rfc6298RttEstimator())
                .build();
    }

    @After
    public void tearDown() {
        conn.close();
        channel.finishAndReleaseAll();
    }

    // ── Initial state ─────────────────────────────────────────────────────────

    @Test
    public void initial_rto_is_default() {
        assertEquals(TcpConstants.RTO_INIT_MS, conn.rttEstimator().rtoMs(conn));
    }

    // ── First sample (RFC 6298 §2.2) ─────────────────────────────────────────

    @Test
    public void first_sample_sets_srtt_and_rttvar() {
        // R = 100ms = 100_000 µs
        // SRTT = 100_000, RTTVAR = 50_000
        // RTO  = SRTT + max(1000, 4×RTTVAR) = 100_000 + 200_000 = 300_000 µs = 300 ms
        conn.rttEstimator().addSample(conn, 100_000L);
        assertEquals(300L, conn.rttEstimator().rtoMs(conn));
    }

    @Test
    public void first_sample_clears_backoff() {
        conn.rttEstimator().backoff(conn);
        conn.rttEstimator().addSample(conn, 100_000L);
        // backoffShift reset to 0 by new sample
        assertEquals(300L, conn.rttEstimator().rtoMs(conn));
    }

    // ── Karn's algorithm ──────────────────────────────────────────────────────

    @Test
    public void karn_negative_rtt_skipped() {
        conn.rttEstimator().addSample(conn, 100_000L);
        long rtoBefore = conn.rttEstimator().rtoMs(conn);
        conn.rttEstimator().addSample(conn, -1L);  // retransmitted segment — must be skipped
        assertEquals(rtoBefore, conn.rttEstimator().rtoMs(conn));
    }

    // ── Subsequent samples (RFC 6298 §2.3) ───────────────────────────────────

    @Test
    public void subsequent_samples_converge() {
        conn.rttEstimator().addSample(conn, 100_000L);
        conn.rttEstimator().addSample(conn, 100_000L);
        // RTTVAR converges toward 0 with identical RTTs; RTO remains >= RTO_MIN_MS
        long rtoMs = conn.rttEstimator().rtoMs(conn);
        assertTrue(rtoMs >= TcpConstants.RTO_MIN_MS);
        assertTrue(rtoMs <= TcpConstants.RTO_MAX_MS);
    }

    @Test
    public void high_variance_inflates_rto() {
        conn.rttEstimator().addSample(conn, 100_000L);   //  100 ms
        conn.rttEstimator().addSample(conn, 500_000L);   //  500 ms
        long rtoMs = conn.rttEstimator().rtoMs(conn);
        // RTTVAR inflated by the large jump; RTO must be > 300ms initial estimate
        assertTrue(rtoMs > 300L);
    }

    // ── Backoff ───────────────────────────────────────────────────────────────

    @Test
    public void backoff_doubles_rto() {
        conn.rttEstimator().addSample(conn, 100_000L);   // RTO = 300 ms
        long rtoBefore = conn.rttEstimator().rtoMs(conn);

        conn.rttEstimator().backoff(conn);
        long rtoAfter = conn.rttEstimator().rtoMs(conn);

        assertEquals(rtoBefore * 2, rtoAfter);
    }

    @Test
    public void backoff_capped_at_rto_max() {
        // Use a large RTT so that even one backoff hits the cap
        // SRTT=10s → baseUs=30_000_000 → shift=1 gives 60_000ms = RTO_MAX_MS
        conn.rttEstimator().addSample(conn, 10_000_000L);
        conn.rttEstimator().backoff(conn);
        assertEquals(TcpConstants.RTO_MAX_MS, conn.rttEstimator().rtoMs(conn));
    }

    @Test
    public void backoff_does_not_exceed_max_after_many_backoffs() {
        conn.rttEstimator().addSample(conn, 100_000L);
        for (int i = 0; i < 20; i++) {
            conn.rttEstimator().backoff(conn);
        }
        assertTrue(conn.rttEstimator().rtoMs(conn) <= TcpConstants.RTO_MAX_MS);
    }

    // ── Reset backoff ─────────────────────────────────────────────────────────

    @Test
    public void reset_backoff_restores_base_rto() {
        conn.rttEstimator().addSample(conn, 100_000L);
        long baseRto = conn.rttEstimator().rtoMs(conn);

        conn.rttEstimator().backoff(conn);
        conn.rttEstimator().backoff(conn);
        assertTrue(conn.rttEstimator().rtoMs(conn) > baseRto);

        conn.rttEstimator().resetBackoff(conn);
        assertEquals(baseRto, conn.rttEstimator().rtoMs(conn));
    }
}

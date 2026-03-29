package com.github.pangolin.routing.upstream.adaptive;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Per-(server, destination) route quality tracker using EWMA-based RTT estimation
 * with circuit breaker support.
 *
 * <p>Thread safety:
 * <ul>
 *   <li>{@code srtt}, {@code rttvar}, {@code circuitOpenUntil} are {@code volatile} — safe to read outside synchronized</li>
 *   <li>{@code nTried} is {@link AtomicInteger} — safe to read outside synchronized</li>
 *   <li>{@code consecutiveFailures} is only read/written inside {@code synchronized(this)}</li>
 *   <li>All mutations go through {@link #onSuccess} or {@link #onFailure} which are synchronized</li>
 * </ul>
 */
public class RouteQuality {
    private static final double ALPHA = 0.25;
    private static final double BETA = 0.25;
    private static final int K = 4;

    volatile long srtt;
    volatile long rttvar;
    volatile long circuitOpenUntil;
    private final AtomicInteger nTried = new AtomicInteger(0);
    private int consecutiveFailures;

    RouteQuality(final long initialSrtt) {
        this.srtt = initialSrtt;
        this.rttvar = 0;
    }

    /** Returns {@code true} if no connection attempt has been recorded yet. */
    public boolean isUntried() {
        return nTried.get() == 0;
    }

    /**
     * Returns the effective score for server selection. Higher score = preferred.
     * Returns 0 if circuit is open (server excluded from selection).
     */
    public double effectiveScore() {
        if (nTried.get() == 0) {
            return 1.0 / srtt;
        }
        if (circuitOpenUntil > System.currentTimeMillis()) {
            return 0.0;
        }
        return 1.0 / (srtt + (long) (K * rttvar));
    }

    public synchronized void onSuccess(final long sample, final int circuitOpenThreshold) {
        if (consecutiveFailures >= circuitOpenThreshold) {
            // Circuit recovery: re-initialize to avoid stale high srtt
            srtt = sample;
            rttvar = sample / 2;
        } else if (nTried.get() == 0) {
            // First sample: initialize per RFC 6298 §2.2
            srtt = sample;
            rttvar = sample / 2;
        } else {
            // EWMA update (Jacobson/Karels)
            final long delta = Math.abs(sample - srtt);
            rttvar = (long) ((1.0 - BETA) * rttvar + BETA * delta);
            srtt = (long) ((1.0 - ALPHA) * srtt + ALPHA * sample);
        }
        consecutiveFailures = 0;
        circuitOpenUntil = 0;
        nTried.incrementAndGet();
    }

    public synchronized void onFailure(final long penalty, final int circuitOpenThreshold,
                                final long circuitBaseMs, final long circuitMaxMs) {
        // Failures always go through EWMA — no first-sample special case.
        // PENALTY is a fixed value so there's no initialization benefit; the EWMA
        // update is well-defined even from the initial srtt.
        final long delta = Math.abs(penalty - srtt);
        rttvar = (long) ((1.0 - BETA) * rttvar + BETA * delta);
        srtt = (long) ((1.0 - ALPHA) * srtt + ALPHA * penalty);
        consecutiveFailures++;
        nTried.incrementAndGet();
        if (consecutiveFailures >= circuitOpenThreshold) {
            final int excess = consecutiveFailures - circuitOpenThreshold;
            final long backoff = excess >= 63 ? circuitMaxMs
                    : Math.min(circuitBaseMs * (1L << excess), circuitMaxMs);
            circuitOpenUntil = System.currentTimeMillis() + backoff;
        }
    }
}

package com.github.pangolin.routing.acceptor.tun.net.v2.tcp.ext.rtt;

import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.connection.ConnectionKey;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.connection.TcpConnection;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.internal.TcpConstants;

/**
 * RFC 6298 adaptive RTT estimator (SRTT / RTTVAR / RTO).
 *
 * <p>Algorithm:
 * <pre>
 *   First sample:  SRTT = R;  RTTVAR = R/2;  RTO = SRTT + 4×RTTVAR
 *   Subsequent:    RTTVAR = (1-β)×RTTVAR + β×|SRTT-R|  (β = 0.25)
 *                  SRTT   = (1-α)×SRTT   + α×R          (α = 0.125)
 *                  RTO    = SRTT + 4×RTTVAR
 * </pre>
 *
 * <p><b>Karn's Algorithm (RFC 6298 §4)</b>: callers must pass {@code rttUs = -1} for
 * retransmitted segments; {@link #addSample} silently skips those calls.
 */
public final class Rfc6298RttEstimator implements RttEstimator {

    private static final ConnectionKey<State> KEY = ConnectionKey.of("rfc6298.estimator");

    private static final class State {
        long srttUs    = 0;      // SRTT in µs; 0 = no sample yet
        long rttvarUs  = 0;      // RTTVAR in µs
        int  backoffShift = 0;   // RTO = base << backoffShift (exponential backoff counter)
    }

    @Override
    public void init(TcpConnection conn) {
        conn.setAttr(KEY, new State());
    }

    @Override
    public void addSample(TcpConnection conn, long rttUs) {
        if (rttUs < 0) return;   // Karn's algorithm: skip retransmitted segments
        State s = conn.getAttr(KEY);
        if (s.srttUs == 0) {
            // First measurement (RFC 6298 §2.2)
            s.srttUs   = rttUs;
            s.rttvarUs = rttUs / 2;
        } else {
            // Subsequent (RFC 6298 §2.3)  — integer approximation using shift arithmetic
            long diff  = Math.abs(s.srttUs - rttUs);
            s.rttvarUs = (3 * s.rttvarUs + diff) / 4;          // β = 0.25
            s.srttUs   = (7 * s.srttUs + rttUs) / 8;           // α = 0.125
        }
        s.backoffShift = 0;  // new ACK → reset backoff
    }

    @Override
    public long rtoMs(TcpConnection conn) {
        State s = conn.getAttr(KEY);
        long baseUs;
        if (s.srttUs == 0) {
            baseUs = TcpConstants.RTO_INIT_MS * 1_000L;
        } else {
            baseUs = s.srttUs + Math.max(1_000L, 4 * s.rttvarUs);  // 1 ms clock granularity
        }
        long rtoMs = (baseUs << s.backoffShift) / 1_000L;
        return Math.min(Math.max(rtoMs, TcpConstants.RTO_MIN_MS), TcpConstants.RTO_MAX_MS);
    }

    @Override
    public void backoff(TcpConnection conn) {
        State s = conn.getAttr(KEY);
        // Cap backoff so RTO doesn't exceed RTO_MAX
        if (s.backoffShift < 6) {
            s.backoffShift++;
        }
    }

    @Override
    public void resetBackoff(TcpConnection conn) {
        State s = conn.getAttr(KEY);
        s.backoffShift = 0;
    }

    @Override
    public void onConnectionClosed(TcpConnection conn) {
        conn.removeAttr(KEY);
    }
}

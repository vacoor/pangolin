package com.github.pangolin.routing.acceptor.tun.net.v2.tcp.ext.rtt;

import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.connection.TcpConnection;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.internal.TcpConstants;

public final class Rfc6298RttEstimator implements RttEstimator {

    @Override
    public long rtoMs(TcpConnection conn) {
        long baseMs;
        if (conn.srttUs() <= 0L) {
            baseMs = TcpConstants.RTO_INIT_MS;
        } else {
            long varianceUs = Math.max(4L * conn.rttvarUs(), 0L);
            long baseUs = conn.srttUs() + varianceUs;
            baseMs = Math.max((baseUs + 999L) / 1_000L, TcpConstants.RTO_MIN_MS);
        }
        long shifted = shiftWithCap(baseMs, conn.rtoBackoffShift());
        return Math.min(Math.max(shifted, TcpConstants.RTO_MIN_MS), TcpConstants.RTO_MAX_MS);
    }

    @Override
    public void addSample(TcpConnection conn, long measuredRttUs) {
        if (measuredRttUs < 0L) {
            return;
        }
        if (conn.srttUs() <= 0L) {
            conn.srttUs(measuredRttUs);
            conn.rttvarUs(measuredRttUs / 2L);
        } else {
            long srtt = conn.srttUs();
            long rttvar = conn.rttvarUs();
            long err = Math.abs(srtt - measuredRttUs);
            conn.rttvarUs((3L * rttvar + err) / 4L);
            conn.srttUs((7L * srtt + measuredRttUs) / 8L);
        }
        conn.rtoBackoffShift(0);
    }

    @Override
    public void backoff(TcpConnection conn) {
        conn.rtoBackoffShift(conn.rtoBackoffShift() + 1);
    }

    @Override
    public void resetBackoff(TcpConnection conn) {
        conn.rtoBackoffShift(0);
    }

    private long shiftWithCap(long value, int shift) {
        long result = value;
        for (int i = 0; i < shift; i++) {
            if (result >= TcpConstants.RTO_MAX_MS) {
                return TcpConstants.RTO_MAX_MS;
            }
            result <<= 1;
            if (result < 0L) {
                return TcpConstants.RTO_MAX_MS;
            }
        }
        return result;
    }
}

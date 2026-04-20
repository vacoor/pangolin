package com.github.pangolin.routing.acceptor.tun.net.v2.tcp.core;

/**
 * RFC 6298 / Linux {@code tcp_rtt_estimator} 的 SRTT / RTTVAR 采样算法。
 *
 * <p>RTO 的计算侧由 {@link TcpSock#rtoMs()} 直接读 {@code srtt / rttvar / rtoBackoffShift},
 * 不走 SPI(见 {@link RttEstimator} 注释)。
 */
public final class Rfc6298RttEstimator implements RttEstimator {

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
}

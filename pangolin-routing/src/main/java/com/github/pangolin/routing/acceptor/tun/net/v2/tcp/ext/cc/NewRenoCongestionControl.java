package com.github.pangolin.routing.acceptor.tun.net.v2.tcp.ext.cc;

import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.connection.ConnectionKey;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.connection.TcpConnection;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.internal.TcpConstants;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.internal.TcpSequence;

import java.util.function.Consumer;

/**
 * RFC 5681 New Reno congestion control.
 *
 * <p>States:
 * <ul>
 *   <li>OPEN   — slow start or congestion avoidance (normal path)</li>
 *   <li>RECOVERY — fast recovery after 3 dupACKs (fast retransmit)</li>
 *   <li>LOSS   — after RTO; slow-start restart from cwnd=1</li>
 * </ul>
 *
 * <p>Key fixes vs. naive implementation:
 * <ul>
 *   <li><b>CA fractional counter</b> ({@code caIncrCounter}): matches Linux
 *       {@code snd_cwnd_cnt}; cwnd increments by 1 per RTT, not per ACK.</li>
 *   <li><b>LOSS exit</b>: when first new ACK arrives after RTO, transition LOSS→OPEN
 *       so cwnd resumes slow-start growth (not stuck at 1 forever).</li>
 *   <li><b>ackedSegs ≥ 1 guard</b>: handled by caller ({@link com.github.pangolin.routing.acceptor.tun.net.v2.tcp.core.TcpIncomingAckHandler}).</li>
 * </ul>
 */
public final class NewRenoCongestionControl implements CongestionControl {

    private static final ConnectionKey<RenoState> KEY = ConnectionKey.of("rfc5681.newreno");

    private enum CongestionState { OPEN, RECOVERY, LOSS }

    private static final class RenoState {
        int  cwnd           = TcpConstants.TCP_INIT_CWND;
        int  ssthresh       = Integer.MAX_VALUE;
        int  dupacks        = 0;
        int  caIncrCounter  = 0;   // Linux snd_cwnd_cnt equivalent
        CongestionState caState = CongestionState.OPEN;
        int  highSeq;              // SND.NXT at RECOVERY entry (RFC 5681 §3.2 "recover" point)
        Consumer<TcpConnection> retransmitCallback;
    }

    @Override
    public String name() { return "newreno"; }

    @Override
    public void init(TcpConnection conn, Consumer<TcpConnection> retransmitCallback) {
        RenoState s = new RenoState();
        s.retransmitCallback = retransmitCallback;
        conn.setAttr(KEY, s);
    }

    @Override
    public void onAck(TcpConnection conn, int ackedSegments, boolean sndUnaAdvanced) {
        RenoState s = conn.getAttr(KEY);
        if (!sndUnaAdvanced) {
            // Duplicate ACK
            if (++s.dupacks == 3 && s.caState == CongestionState.OPEN) {
                // Enter Fast Recovery (RFC 5681 §3.2)
                s.ssthresh      = Math.max(s.cwnd / 2, 2);
                s.cwnd          = s.ssthresh + 3;  // inflate: 3 dupACKs == 3 pkts left network
                s.highSeq       = conn.sndNxt();
                s.caState       = CongestionState.RECOVERY;
                s.caIncrCounter = 0;
                s.retransmitCallback.accept(conn);
            } else if (s.caState == CongestionState.RECOVERY) {
                s.cwnd++;   // RFC 5681 §3.2: inflate by 1 per extra dupACK in recovery
            }
        } else {
            // New data acknowledged (SND.UNA advanced)
            if (s.caState == CongestionState.RECOVERY
                    && TcpSequence.after(conn.sndUna(), s.highSeq)) {
                // Full ACK — exit Fast Recovery (RFC 5681 §3.2 step 6)
                s.cwnd          = s.ssthresh;
                s.caState       = CongestionState.OPEN;
                s.caIncrCounter = 0;
            } else if (s.caState == CongestionState.LOSS) {
                // First new ACK after RTO — exit LOSS, re-enter slow start.
                // Without this, cwnd stays at 1 forever after any timeout.
                s.caState       = CongestionState.OPEN;
                s.caIncrCounter = 0;
            }
            s.dupacks = 0;

            if (s.cwnd < s.ssthresh) {
                // Slow start: exponential growth
                s.cwnd += ackedSegments;
            } else {
                // Congestion avoidance: ~1 SMSS per RTT (RFC 5681 §3.1).
                // Using fractional counter (Linux snd_cwnd_cnt) to avoid growing cwnd
                // by up to cwnd every RTT (which the naive Math.max(1, acked/cwnd) does).
                s.caIncrCounter += ackedSegments;
                if (s.caIncrCounter >= s.cwnd) {
                    s.cwnd++;
                    s.caIncrCounter = 0;
                }
            }
        }
    }

    @Override
    public void onTimeout(TcpConnection conn) {
        RenoState s = conn.getAttr(KEY);
        // RFC 5681 §3.1: ssthresh = max(FlightSize/2, 2); cwnd = 1 (slow-start restart)
        s.ssthresh      = Math.max(s.cwnd / 2, 2);
        s.cwnd          = 1;
        s.dupacks       = 0;
        s.caIncrCounter = 0;
        s.caState       = CongestionState.LOSS;
    }

    @Override
    public int cwnd(TcpConnection conn) {
        return conn.getAttr(KEY).cwnd;
    }

    @Override
    public boolean isInRecovery(TcpConnection conn) {
        RenoState s = conn.getAttr(KEY);
        return s != null && s.caState != CongestionState.OPEN;
    }

    @Override
    public void onConnectionClosed(TcpConnection conn) {
        conn.removeAttr(KEY);
    }
}

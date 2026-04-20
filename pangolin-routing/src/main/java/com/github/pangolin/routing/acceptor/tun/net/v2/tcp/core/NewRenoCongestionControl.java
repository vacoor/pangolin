package com.github.pangolin.routing.acceptor.tun.net.v2.tcp.core;

import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.core.TcpConnection;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.core.TcpConstants;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.core.TcpSequence;

public final class NewRenoCongestionControl implements CongestionControl {

    private static final String STATE_OPEN = "OPEN";
    private static final String STATE_RECOVERY = "RECOVERY";
    private static final String STATE_LOSS = "LOSS";

    @Override
    public int cwnd(TcpConnection conn) {
        return conn.cwnd();
    }

    @Override
    public boolean isInRecovery(TcpConnection conn) {
        return !STATE_OPEN.equals(conn.congestionState());
    }

    @Override
    public void onAck(TcpConnection conn, int ackedSegs, boolean newAck) {
        if (newAck) {
            onNewAck(conn, ackedSegs);
            return;
        }
        onDupAck(conn);
    }

    @Override
    public void onTimeout(TcpConnection conn) {
        conn.ssthresh(Math.max(conn.cwnd() / 2, 2));
        conn.cwnd(1);
        conn.dupacks(0);
        conn.caIncrCounter(0);
        conn.congestionState(STATE_LOSS);
    }

    private void onNewAck(TcpConnection conn, int ackedSegs) {
        conn.dupacks(0);
        if (STATE_RECOVERY.equals(conn.congestionState())) {
            if (TcpSequence.after(conn.sndUna(), conn.highSeq())) {
                conn.congestionState(STATE_OPEN);
                conn.cwnd(conn.ssthresh());
                conn.caIncrCounter(0);
            }
            return;
        }
        if (STATE_LOSS.equals(conn.congestionState())) {
            conn.congestionState(STATE_OPEN);
        }
        if (conn.cwnd() < conn.ssthresh()) {
            conn.cwnd(conn.cwnd() + Math.max(ackedSegs, 0));
            return;
        }
        conn.caIncrCounter(conn.caIncrCounter() + Math.max(ackedSegs, 0));
        while (conn.caIncrCounter() >= conn.cwnd()) {
            conn.caIncrCounter(conn.caIncrCounter() - conn.cwnd());
            conn.cwnd(conn.cwnd() + 1);
        }
    }

    private void onDupAck(TcpConnection conn) {
        if (STATE_RECOVERY.equals(conn.congestionState())) {
            conn.cwnd(conn.cwnd() + 1);
            return;
        }
        if (STATE_LOSS.equals(conn.congestionState())) {
            return;
        }
        int dupacks = conn.dupacks() + 1;
        conn.dupacks(dupacks);
        if (dupacks < 3) {
            return;
        }
        if (dupacks == 3) {
            int ssthresh = Math.max(conn.cwnd() / 2, 2);
            conn.ssthresh(ssthresh);
            conn.highSeq(conn.sndNxt());
            conn.cwnd(ssthresh + 3);
            conn.caIncrCounter(0);
            conn.congestionState(STATE_RECOVERY);
            conn.fireFastRetransmit();
            return;
        }
        conn.cwnd(conn.cwnd() + 1);
    }
}

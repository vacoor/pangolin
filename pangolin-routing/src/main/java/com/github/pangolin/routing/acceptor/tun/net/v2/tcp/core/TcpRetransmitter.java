package com.github.pangolin.routing.acceptor.tun.net.v2.tcp.core;

import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.connection.TcpConnection;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.connection.TcpSendBuffer.TcpSegmentEntry;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.internal.FourTuple;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.pipeline.TcpConnectionChannel;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.timer.TimerType;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.timer.TcpTimerScheduler;
import io.netty.buffer.ByteBuf;

/**
 * RFC 9293 retransmission: re-sends the oldest unacknowledged segment.
 * Called by:
 * <ul>
 *   <li>{@link com.github.pangolin.routing.acceptor.tun.net.v2.tcp.ext.cc.CongestionControl}
 *       on fast retransmit (3 dupACKs)</li>
 *   <li>The retransmit timer on RTO expiry</li>
 * </ul>
 */
public final class TcpRetransmitter {

    public static final TcpRetransmitter INSTANCE = new TcpRetransmitter();

    private TcpRetransmitter() {}

    /**
     * Retransmit the oldest unacknowledged segment.
     * Used as the {@code retransmitCallback} injected into {@code CongestionControl.init()}.
     */
    public void retransmit(TcpConnection conn) {
        TcpSegmentEntry oldest = conn.sendBuffer().peekRtx();
        if (oldest == null) return;

        oldest.markRetransmitted();
        oldest.updateSentTime(System.nanoTime() / 1_000L);

        FourTuple ft = ((TcpConnectionChannel) conn.channel()).fourTuple();

        int payLen = oldest.dataLen();
        int flags  = oldest.isFin() ? 0x11 /* FIN+ACK */ : 0x18 /* PSH+ACK */;
        int wnd    = conn.rcvWnd() >> conn.rcvWscale();
        if (wnd > 65535) wnd = 65535;

        ByteBuf buf = TcpPacketBuilder.buildRaw(
                ft.dstAddrBytes(), ft.dstPort(),
                ft.srcAddrBytes(), ft.srcPort(),
                oldest.startSeq(), conn.rcvNxt(),
                flags, wnd,
                null, oldest.payload(), payLen);

        conn.channel().writeAndFlush(buf);
    }

    /**
     * Handle RTO expiry:
     * <ol>
     *   <li>Notify CC (halve cwnd, enter LOSS)</li>
     *   <li>Backoff RTO (RFC 6298 §5.5)</li>
     *   <li>Retransmit the oldest segment</li>
     *   <li>Reschedule the retransmit timer</li>
     * </ol>
     */
    public void onTimeout(TcpConnection conn) {
        conn.congestionControl().onTimeout(conn);
        conn.rttEstimator().backoff(conn);
        retransmit(conn);
        scheduleRetransmit(conn);
    }

    /** Arm the retransmit timer for the current RTO. */
    public void scheduleRetransmit(TcpConnection conn) {
        long rtoMs = conn.rttEstimator().rtoMs(conn);
        TcpTimerScheduler.INSTANCE.scheduleWriteTimer(
                conn, TimerType.RETRANSMIT, rtoMs, () -> onTimeout(conn));
    }

    /** Cancel the retransmit timer (called when RTX queue becomes empty). */
    public void cancelRetransmit(TcpConnection conn) {
        TcpTimerScheduler.INSTANCE.cancelWriteTimer(conn);
    }
}

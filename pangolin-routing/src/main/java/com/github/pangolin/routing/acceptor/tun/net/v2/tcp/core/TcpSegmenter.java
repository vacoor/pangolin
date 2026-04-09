package com.github.pangolin.routing.acceptor.tun.net.v2.tcp.core;

import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.connection.TcpConnection;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.connection.TcpSendBuffer.TcpSegmentEntry;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.internal.FourTuple;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.pipeline.TcpConnectionChannel;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;

/**
 * TCP segment transmitter: segments application data and sends it within the congestion
 * and receive windows.
 *
 * <p>Stateless — all state lives in {@link TcpConnection}.
 */
public final class TcpSegmenter {

    public static final TcpSegmenter INSTANCE = new TcpSegmenter();

    private TcpSegmenter() {}

    /**
     * Drain the send buffer: segment and transmit pending data within the current
     * congestion window ({@code cwnd}) and peer receive window ({@code sndWnd}).
     *
     * @param conn the connection whose send buffer to drain
     */
    public void sendPending(TcpConnection conn) {
        int cwnd   = conn.congestionControl().cwnd(conn);
        int inFlight = conn.sendBuffer().rtxQueueSize();

        while (conn.sendBuffer().hasDataToSend() && inFlight < cwnd) {
            ByteBuf data = conn.sendBuffer().pollWrite();
            if (data == null) break;

            int mss   = conn.mss();
            int total = data.readableBytes();
            int offset = 0;

            while (offset < total && inFlight < cwnd) {
                int segLen = Math.min(mss, total - offset);
                ByteBuf payload = data.slice(offset, segLen).retain();
                offset += segLen;
                sendSegment(conn, payload, false);
                // Release the local retain: sendSegment enqueues a retainedSlice into the RTX
                // queue (which holds its own +1), so payload's +1 from .retain() is no longer
                // needed here and must be released to avoid a reference-count leak.
                payload.release();
                inFlight++;
            }

            // If we couldn't send all data (cwnd exceeded), put remaining back
            if (offset < total) {
                conn.sendBuffer().enqueue(data.slice(offset, total - offset).retain());
            }
            data.release();
        }
    }

    /**
     * Send a FIN segment (FIN+ACK).
     * Advances SND.NXT by 1 to account for the FIN sequence number.
     */
    public void sendFin(TcpConnection conn) {
        FourTuple ft = ((TcpConnectionChannel) conn.channel()).fourTuple();
        int seq = conn.sndNxt();
        int ack = conn.rcvNxt();

        ByteBuf buf = TcpPacketBuilder.buildRaw(
                ft.dstAddrBytes(), ft.dstPort(),
                ft.srcAddrBytes(), ft.srcPort(),
                seq, ack, 0x11 /* FIN+ACK */,
                scaledWindow(conn), null, null, 0);

        conn.sndNxt(conn.sndNxt() + 1);   // FIN consumes one sequence number

        conn.channel().writeAndFlush(buf);
    }

    /**
     * Send a pure ACK (no data).
     */
    public void sendAck(TcpConnection conn) {
        FourTuple ft = ((TcpConnectionChannel) conn.channel()).fourTuple();
        ByteBuf buf = TcpPacketBuilder.buildRaw(
                ft.dstAddrBytes(), ft.dstPort(),
                ft.srcAddrBytes(), ft.srcPort(),
                conn.sndNxt(), conn.rcvNxt(),
                0x10 /* ACK */,
                scaledWindow(conn), null, null, 0);
        conn.channel().writeAndFlush(buf);
    }

    /**
     * Send a RST+ACK.
     */
    public void sendRstAck(TcpConnection conn) {
        FourTuple ft = ((TcpConnectionChannel) conn.channel()).fourTuple();
        ByteBuf buf = TcpPacketBuilder.buildRaw(
                ft.dstAddrBytes(), ft.dstPort(),
                ft.srcAddrBytes(), ft.srcPort(),
                conn.sndNxt(), conn.rcvNxt(),
                0x14 /* RST+ACK */,
                0, null, null, 0);
        conn.channel().writeAndFlush(buf);
    }

    // ── Private helpers ──────────────────────────────────────────────────

    private void sendSegment(TcpConnection conn, ByteBuf payload, boolean fin) {
        FourTuple ft  = ((TcpConnectionChannel) conn.channel()).fourTuple();
        int seq       = conn.sndNxt();
        int payLen    = payload.readableBytes();
        long sentTime = System.nanoTime() / 1_000L;

        // PSH on last segment of a write (heuristic: always set PSH)
        int flags = fin ? 0x11 /* FIN+ACK */ : 0x18 /* PSH+ACK */;

        ByteBuf buf = TcpPacketBuilder.buildRaw(
                ft.dstAddrBytes(), ft.dstPort(),
                ft.srcAddrBytes(), ft.srcPort(),
                seq, conn.rcvNxt(),
                flags, scaledWindow(conn),
                null, payload, payLen);

        // Track in RTX queue before sending
        TcpSegmentEntry entry = new TcpSegmentEntry(
                payload.retainedSlice(), seq, payLen, fin, sentTime);
        conn.sendBuffer().enqueueRtx(entry);
        conn.sndNxt(conn.sndNxt() + payLen + (fin ? 1 : 0));

        conn.channel().writeAndFlush(buf);
    }

    private static int scaledWindow(TcpConnection conn) {
        int wnd = conn.rcvWnd() >> conn.rcvWscale();
        return Math.min(Math.max(wnd, 0), 65535);
    }
}

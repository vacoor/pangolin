package com.github.pangolin.routing.acceptor.tun.net.v2.tcp.ng;

import com.github.pangolin.routing.acceptor.tun.net.handler.support.TcpPacketBuf;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.connection.TcpConnection;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.connection.TcpConnectionState;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.connection.TcpSendBuffer.TcpSegmentEntry;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.core.SkbDropReasonConstants;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.core.TcpOutput;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.core.TcpRetransmitter;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.established.TcpDataHandler;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.handshake.TcpHandshaker;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.handshake.TcpHandshakerFactory;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.internal.FourTuple;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.internal.TcpConfig;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;

import java.util.HashMap;

import static com.github.pangolin.routing.acceptor.tun.net.handler.tcp.util.TcpUtils.determineEndSeq;
import static com.github.pangolin.routing.acceptor.tun.net.v2.tcp.internal.TcpConstants.TCP_INIT_CWND;
import static com.github.pangolin.routing.acceptor.tun.net.v2.tcp.internal.TcpConstants.TCP_MSS_DEFAULT;
import static com.github.pangolin.routing.acceptor.tun.net.v2.tcp.internal.TcpSequence.after;
import static com.github.pangolin.routing.acceptor.tun.net.v2.tcp.internal.TcpSequence.before;

/**
 * v2 TCP multiplexer without Netty handler chain.
 * TUN ingress calls this class directly.
 */
public final class TcpMultiplexer {
    private static final int FLAG_DATA = 0x01;
    private static final int FLAG_WIN_UPDATE = 0x02;
    private static final int FLAG_SLOWPATH = 0x100;
    private static final int FLAG_SND_UNA_ADVANCED = 0x400;
    private static final int FLAG_UPDATE_TS_RECENT = 0x4000;
    private static final int FLAG_NO_CHALLENGE_ACK = 0x8000;

    @FunctionalInterface
    public interface DataConsumer {
        void onData(FourTuple key, ByteBuf data);
    }

    private static final DataConsumer DROP_DATA = (key, data) -> data.release();

    private final TcpHandshakerFactory handshakerFactory;
    private final HashMap<FourTuple, TcpHandshaker> synRegistry = new HashMap<>();
    private final HashMap<FourTuple, TcpConnection> establishedRegistry = new HashMap<>();
    private final DataConsumer dataConsumer;

    public TcpMultiplexer(TcpConfig config) {
        this(config, DROP_DATA);
    }

    public TcpMultiplexer(TcpConfig config, DataConsumer dataConsumer) {
        this.handshakerFactory = new TcpHandshakerFactory(config);
        this.dataConsumer = dataConsumer == null ? DROP_DATA : dataConsumer;
    }

    public void tcp_rcv(ChannelHandlerContext ctx, TcpPacketBuf pkt) {
        final FourTuple key = FourTuple.of(pkt);

        TcpConnection conn = establishedRegistry.get(key);
        if (conn != null) {
            tcp_rcv_established(ctx, key, conn, pkt);
            return;
        }

        TcpHandshaker handshaker = synRegistry.get(key);
        if (handshaker != null) {
            TcpConnection nsk = handshaker.finishHandshake(ctx.channel(), pkt);
            if (nsk == null) {
                return;
            }
            synRegistry.remove(key);
            establishedRegistry.put(key, nsk);
            tcp_rcv_established(ctx, key, nsk, pkt);
            return;
        }

        // LISTEN state
        if (pkt.isAck()) {
            TcpOutput.INSTANCE.tcp_v4_send_reset(ctx, pkt);
            return;
        }
        if (pkt.isRst()) {
            return;
        }
        if (!pkt.isSyn() || pkt.isFin()) {
            return;
        }
        TcpHandshaker hs = handshakerFactory.newHandshaker(pkt);
        synRegistry.put(key, hs);
        hs.handshake(ctx.channel(), pkt);
    }

    /**
     * Data-consume entry for TUN ingress.
     */
    public void consume(ChannelHandlerContext ctx, TcpPacketBuf pkt) {
        tcp_rcv(ctx, pkt);
    }

    /**
     * Data-write entry for application side.
     * Uses v2 send chain: SegmentEntry -> TcpSendBuffer -> tcp_write_xmit.
     */
    public boolean write(FourTuple key, ByteBuf data) {
        TcpConnection conn = establishedRegistry.get(key);
        if (conn == null || !conn.state().canSend()) {
            data.release();
            return false;
        }
        conn.tcp_queue_skb(new TcpSegmentEntry(
                data, conn.writeSeq(), data.readableBytes(),
                (byte) com.github.pangolin.routing.acceptor.tun.net.v2.tcp.internal.TcpConstants.TCPHDR_ACK,
                0L));
        TcpOutput.INSTANCE.tcp_write_xmit(
                conn, conn.mss(),
                com.github.pangolin.routing.acceptor.tun.net.v2.tcp.internal.TcpConstants.TCP_NAGLE_OFF,
                0);
        return true;
    }

    private void tcp_rcv_established(ChannelHandlerContext ctx, FourTuple key, TcpConnection conn, TcpPacketBuf pkt) {
        if (pkt.isRst()) {
            conn.close();
            establishedRegistry.remove(key);
            return;
        }
        if (!pkt.isAck() && !pkt.isSyn()) {
            return;
        }
        if (!tcpSequenceAcceptable(conn, pkt)) {
            if (!pkt.isRst()) {
                TcpOutput.INSTANCE.tcp_send_challenge_ack(conn, false);
            }
            return;
        }
        if (pkt.isSyn()) {
            TcpOutput.INSTANCE.tcp_send_challenge_ack(conn, false);
            return;
        }

        final int priorSndUna = conn.sndUna();
        if (pkt.isAck() && !processAck(conn, pkt)) {
            TcpOutput.INSTANCE.tcp_send_challenge_ack(conn, false);
            return;
        }

        if (conn.state() == TcpConnectionState.TCP_SYN_RECV && after(conn.sndUna(), priorSndUna)) {
            tcpInitWl(conn, pkt.tcpSeq());
            conn.state(TcpConnectionState.TCP_ESTABLISHED);
            tcpInitTransfer(conn);
            conn.rcvMss(tcpInitializeRcvMss(conn));
        }

        if (pkt.tcpPayloadLength() > 0 && conn.state().canReceive()) {
            ByteBuf data = TcpDataHandler.INSTANCE.onData(conn, pkt);
            if (data != null) {
                dataConsumer.onData(key, data);
            }
            TcpOutput.INSTANCE.tcp_send_ack(conn);
        }

        if (pkt.isFin() && conn.state().canReceive()) {
            conn.rcvNxt(conn.rcvNxt() + 1);
            conn.state(TcpConnectionState.CLOSE_WAIT);
            TcpOutput.INSTANCE.tcp_send_ack(conn);
        }

        if (conn.tcpSendHead() != null) {
            TcpOutput.INSTANCE.tcp_write_xmit(
                    conn,
                    conn.mss(),
                    com.github.pangolin.routing.acceptor.tun.net.v2.tcp.internal.TcpConstants.TCP_NAGLE_OFF,
                    0
            );
        }
    }

    private static boolean tcpSequenceAcceptable(TcpConnection conn, TcpPacketBuf pkt) {
        int seq = pkt.tcpSeq();
        int segLen = pkt.tcpPayloadLength()
                + (pkt.isSyn() ? 1 : 0)
                + (pkt.isFin() ? 1 : 0);
        int endSeq = seq + segLen;
        int rcvWup = conn.rcvWup();
        int rcvWndEnd = conn.rcvNxt() + conn.tcp_receive_window();

        if (before(endSeq, rcvWup)) {
            return false;
        }
        if (after(endSeq, rcvWndEnd)) {
            return !after(seq, rcvWndEnd);
        }
        return true;
    }

    private boolean processAck(TcpConnection conn, TcpPacketBuf pkt) {
        if (!pkt.isAck()) {
            return true;
        }
        final int priorSndUna = conn.sndUna();
        final int priorPktsOut = conn.packetsOut();
        int result = tcpAck(conn, pkt, FLAG_SLOWPATH | FLAG_UPDATE_TS_RECENT | FLAG_NO_CHALLENGE_ACK);
        if (result < 0) {
            return false;
        }
        if (after(conn.sndUna(), priorSndUna)) {
            TcpRetransmitter.INSTANCE.rearmRto(conn);
            if (priorPktsOut > 0) {
                int newlyAcked = Math.max(1, priorPktsOut - conn.packetsOut());
                conn.congestionControl().onAck(conn, newlyAcked, true);
                conn.rttEstimator().resetBackoff(conn);
            }
        }
        return true;
    }

    private int tcpAck(TcpConnection conn, TcpPacketBuf pkt, int flags) {
        final int priorSndUna = conn.sndUna();
        final int priorPktsOut = conn.packetsOut();
        final int ackSeq = pkt.tcpSeq();
        final int ack = pkt.tcpAckNum();

        if (before(ack, priorSndUna)) {
            final int maxWindow = (int) Math.min(conn.maxWindow(), conn.bytesAcked());
            if (before(ack, priorSndUna - maxWindow)) {
                if (0 == (flags & FLAG_NO_CHALLENGE_ACK)) {
                    TcpOutput.INSTANCE.tcp_send_challenge_ack(conn, false);
                }
                return -SkbDropReasonConstants.SKB_DROP_REASON_TCP_TOO_OLD_ACK;
            }
            return 0;
        }

        if (after(ack, conn.sndNxt())) {
            return -SkbDropReasonConstants.SKB_DROP_REASON_TCP_ACK_UNSENT_DATA;
        }

        if (after(ack, priorSndUna)) {
            flags |= FLAG_SND_UNA_ADVANCED;
        }

        if (0 != (flags & FLAG_UPDATE_TS_RECENT)) {
            conn.timestampExt().updateRecent(conn, ackSeq);
        }

        if ((flags & (FLAG_SLOWPATH | FLAG_SND_UNA_ADVANCED)) == FLAG_SND_UNA_ADVANCED) {
            conn.sndWl1(ackSeq);
            flags |= FLAG_WIN_UPDATE;
        } else {
            if (ackSeq != determineEndSeq(pkt)) {
                flags |= FLAG_DATA;
            }
            flags |= tcpAckUpdateWindow(conn, pkt, 0 != (flags & FLAG_SND_UNA_ADVANCED));
        }

        conn.sndUnaUpdate(ack);
        if (priorPktsOut == 0) {
            tcpAckProbe(conn);
            return 1;
        }

        long rttUs = rttSample(conn, pkt);
        conn.rttEstimator().addSample(conn, rttUs);
        conn.cleanRtxQueue(ack);
        conn.lossDetector().onAck(conn, ack, flags);
        return 1;
    }

    private static void tcpAckProbe(TcpConnection conn) {
        if (conn.tcpSendHead() == null) {
            return;
        }
        if (conn.sndWnd() > 0) {
            TcpRetransmitter.INSTANCE.cancelRetransmit(conn);
        }
    }

    private static int tcpAckUpdateWindow(TcpConnection conn, TcpPacketBuf pkt, boolean newDataAcked) {
        int seg = pkt.tcpSeq();
        int nwin = pkt.tcpWindow() << conn.sndWscale();
        if (newDataAcked
                || after(seg, conn.sndWl1())
                || (seg == conn.sndWl1() && (before(conn.sndWnd(), nwin) || nwin == 0))) {
            conn.sndWl1(seg);
            conn.sndWnd(nwin);
            return FLAG_WIN_UPDATE;
        }
        return 0;
    }

    private static long rttSample(TcpConnection conn, TcpPacketBuf pkt) {
        TcpSegmentEntry head = conn.sendBuffer().peekRtx();
        if (head == null) return -1;
        if (head.isRetransmitted()) return -1;
        if (!after(pkt.tcpAckNum(), head.startSeq())) return -1;
        long nowUs = System.nanoTime() / 1_000L;
        return nowUs - head.sentTimeUs();
    }

    private static void tcpInitWl(TcpConnection conn, int seq) {
        conn.sndWl1(seq);
    }

    private static void tcpInitTransfer(TcpConnection conn) {
        // v2 extensions are initialized in TcpConnection.Builder#build.
    }

    private static int tcpInitializeRcvMss(TcpConnection conn) {
        int mss = conn.mss();
        int hint = mss;
        hint = Math.min(hint, conn.rcvWnd() / 2);
        hint = Math.min(hint, TCP_INIT_CWND * mss);
        hint = Math.max(hint, TCP_MSS_DEFAULT);
        return hint;
    }
}

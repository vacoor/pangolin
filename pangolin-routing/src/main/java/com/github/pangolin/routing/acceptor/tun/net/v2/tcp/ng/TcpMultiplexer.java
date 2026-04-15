package com.github.pangolin.routing.acceptor.tun.net.v2.tcp.ng;

import com.github.pangolin.routing.acceptor.tun.net.handler.support.TcpPacketBuf;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.connection.TcpConnection;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.connection.TcpConnectionState;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.connection.TcpSendBuffer.TcpSegmentEntry;
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

import static com.github.pangolin.routing.acceptor.tun.net.v2.tcp.internal.TcpSequence.after;
import static com.github.pangolin.routing.acceptor.tun.net.v2.tcp.internal.TcpSequence.before;

/**
 * v2 TCP multiplexer without Netty handler chain.
 * TUN ingress calls this class directly.
 */
public final class TcpMultiplexer {
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

        final int priorSndUna = conn.sndUna();
        if (pkt.isAck()) {
            int ack = pkt.tcpAckNum();
            if (!before(ack, conn.sndUna()) && !after(ack, conn.sndNxt())) {
                conn.sndUnaUpdate(ack);
                conn.cleanRtxQueue(ack);
                if (after(conn.sndUna(), priorSndUna)) {
                    TcpRetransmitter.INSTANCE.rearmRto(conn);
                }
            } else if (after(ack, conn.sndNxt())) {
                TcpOutput.INSTANCE.tcp_send_challenge_ack(conn, false);
                return;
            }
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
}

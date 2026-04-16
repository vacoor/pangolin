package com.github.pangolin.routing.acceptor.tun.net.v2.tcp.ng;

import com.github.pangolin.routing.acceptor.tun.net.handler.support.TcpPacketBuf;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.connection.TcpConnection;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.connection.TcpConnectionState;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.connection.TcpSendBuffer.TcpSegmentEntry;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.core.TcpIncomingAckHandler;
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

import static com.github.pangolin.routing.acceptor.tun.net.v2.tcp.internal.TcpConstants.TCP_INIT_CWND;
import static com.github.pangolin.routing.acceptor.tun.net.v2.tcp.internal.TcpConstants.TCP_MSS_DEFAULT;
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

    private final ListenSock listenSock;
    private final TcpHandshakerFactory handshakerFactory;
    private final HashMap<FourTuple, TcpHandshaker> synRegistry = new HashMap<>();
    private final HashMap<FourTuple, TcpConnection> establishedRegistry = new HashMap<>();
    private final DataConsumer dataConsumer;

    public TcpMultiplexer(TcpConfig config) {
        this(config, DROP_DATA);
    }

    public TcpMultiplexer(TcpConfig config, DataConsumer dataConsumer) {
        this.listenSock = new ListenSock();
        this.handshakerFactory = new TcpHandshakerFactory(config);
        this.dataConsumer = dataConsumer == null ? DROP_DATA : dataConsumer;
    }

    public void tcp_rcv(ChannelHandlerContext ctx, TcpPacketBuf pkt) {
        tcp_v4_rcv(ctx, pkt);
    }

    private void tcp_v4_rcv(ChannelHandlerContext ctx, TcpPacketBuf pkt) {
        final LookupResult sk = __inet_lookup_skb(pkt);
        try {
            int err = tcp_v4_do_rcv(ctx, sk, pkt);
            if (err != 0) {
                TcpOutput.INSTANCE.tcp_v4_send_reset(ctx, pkt);
                if (sk.kind == SockKind.ESTABLISHED && sk.conn != null) {
                    sk.conn.close();
                    establishedRegistry.remove(sk.key);
                }
            }
        } catch (Throwable cause) {
            if (sk.kind == SockKind.ESTABLISHED && sk.conn != null) {
                sk.conn.close();
                establishedRegistry.remove(sk.key);
            }
            throw cause;
        }
    }

    private LookupResult __inet_lookup_skb(TcpPacketBuf pkt) {
        final FourTuple key = FourTuple.of(pkt);

        TcpConnection conn = establishedRegistry.get(key);
        if (conn != null) {
            return LookupResult.established(key, conn);
        }

        TcpHandshaker handshaker = synRegistry.get(key);
        if (handshaker != null) {
            return LookupResult.synRecv(key, handshaker);
        }
        return LookupResult.listen(key, listenSock);
    }

    private int tcp_v4_do_rcv(ChannelHandlerContext ctx, LookupResult sk, TcpPacketBuf pkt) {
        return tcp_rcv_state_process(ctx, sk, pkt);
    }

    private int tcp_rcv_state_process(ChannelHandlerContext ctx, LookupResult sk, TcpPacketBuf pkt) {
        switch (sk.kind) {
            case LISTEN:
                if (sk.listenSock.state() != TcpConnectionState.TCP_LISTEN) {
                    return -1;
                }
                return tcp_listen_state_process(ctx, sk.key, pkt);
            case SYN_RECV:
                return tcp_syn_recv_state_process(ctx, sk.key, sk.handshaker, pkt);
            case ESTABLISHED:
                return tcp_connected_state_process(ctx, sk.key, sk.conn, pkt);
            default:
                return 0;
        }
    }

    private int tcp_listen_state_process(ChannelHandlerContext ctx, FourTuple key, TcpPacketBuf pkt) {
        if (pkt.isAck()) {
            return -1;
        }
        if (pkt.isRst()) {
            return 0;
        }
        if (!pkt.isSyn() || pkt.isFin()) {
            return 0;
        }
        TcpHandshaker hs = handshakerFactory.newHandshaker(pkt);
        synRegistry.put(key, hs);
        hs.handshake(ctx.channel(), pkt);
        return 0;
    }

    private int tcp_syn_recv_state_process(ChannelHandlerContext ctx, FourTuple key, TcpHandshaker handshaker, TcpPacketBuf pkt) {
        TcpConnection nsk = handshaker.finishHandshake(ctx.channel(), pkt);
        if (nsk == null) {
            return 0;
        }
        synRegistry.remove(key);
        establishedRegistry.put(key, nsk);
        return tcp_connected_state_process(ctx, key, nsk, pkt);
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

    private int tcp_connected_state_process(ChannelHandlerContext ctx, FourTuple key, TcpConnection conn, TcpPacketBuf pkt) {
        switch (conn.state()) {
            case TCP_SYN_RECV:
                return tcp_syn_recv_child_state_process(ctx, key, conn, pkt);
            case TCP_ESTABLISHED:
            case CLOSE_WAIT:
            case FIN_WAIT_1:
            case FIN_WAIT_2:
            case CLOSING:
            case LAST_ACK:
            case TIME_WAIT:
                return tcp_established_like_state_process(ctx, key, conn, pkt);
            default:
                return 0;
        }
    }

    private int tcp_syn_recv_child_state_process(ChannelHandlerContext ctx, FourTuple key, TcpConnection conn, TcpPacketBuf pkt) {
        return tcp_established_like_state_process(ctx, key, conn, pkt);
    }

    private int tcp_established_like_state_process(ChannelHandlerContext ctx, FourTuple key, TcpConnection conn, TcpPacketBuf pkt) {
        if (pkt.isRst()) {
            conn.close();
            establishedRegistry.remove(key);
            return 0;
        }
        if (!pkt.isAck() && !pkt.isSyn()) {
            return 0;
        }
        if (!tcpSequenceAcceptable(conn, pkt)) {
            if (!pkt.isRst()) {
                TcpOutput.INSTANCE.tcp_send_challenge_ack(conn, false);
            }
            return 0;
        }
        if (pkt.isSyn()) {
            TcpOutput.INSTANCE.tcp_send_challenge_ack(conn, false);
            return 0;
        }

        final int priorSndUna = conn.sndUna();
        if (pkt.isAck() && !processAck(conn, pkt)) {
            TcpOutput.INSTANCE.tcp_send_challenge_ack(conn, false);
            return 0;
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
        return 0;
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
        int result = TcpIncomingAckHandler.tcpAck(
                conn, pkt,
                TcpIncomingAckHandler.FLAG_SLOWPATH
                        | TcpIncomingAckHandler.FLAG_UPDATE_TS_RECENT
                        | TcpIncomingAckHandler.FLAG_NO_CHALLENGE_ACK
        );
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

    private enum SockKind {
        LISTEN,
        SYN_RECV,
        ESTABLISHED
    }

    private static final class ListenSock {
        private final TcpConnectionState state = TcpConnectionState.TCP_LISTEN;

        private ListenSock() {
        }

        private TcpConnectionState state() {
            return state;
        }
    }

    private static final class LookupResult {
        private final SockKind kind;
        private final FourTuple key;
        private final ListenSock listenSock;
        private final TcpHandshaker handshaker;
        private final TcpConnection conn;

        private LookupResult(SockKind kind, FourTuple key, ListenSock listenSock, TcpHandshaker handshaker, TcpConnection conn) {
            this.kind = kind;
            this.key = key;
            this.listenSock = listenSock;
            this.handshaker = handshaker;
            this.conn = conn;
        }

        private static LookupResult listen(FourTuple key, ListenSock listenSock) {
            return new LookupResult(SockKind.LISTEN, key, listenSock, null, null);
        }

        private static LookupResult synRecv(FourTuple key, TcpHandshaker handshaker) {
            return new LookupResult(SockKind.SYN_RECV, key, null, handshaker, null);
        }

        private static LookupResult established(FourTuple key, TcpConnection conn) {
            return new LookupResult(SockKind.ESTABLISHED, key, null, null, conn);
        }
    }
}

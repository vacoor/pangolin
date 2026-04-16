package com.github.pangolin.routing.acceptor.tun.net.v2.tcp.ng;

import com.github.pangolin.routing.acceptor.tun.net.handler.support.TcpPacketBuf;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.connection.TcpConnection;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.connection.TcpConnectionState;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.connection.ConnectionKey;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.connection.TcpReceiveBuffer;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.connection.TcpSendBuffer.TcpSegmentEntry;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.connection.TcpSendBuffer;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.core.TcpIncomingAckHandler;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.core.TcpInput;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.core.TcpOutput;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.core.TcpRetransmitter;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.established.TcpDataHandler;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.handshake.TcpHandshaker;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.handshake.TcpHandshakerFactory;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.internal.FourTuple;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.internal.TcpConfig;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.internal.TcpConstants;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.timer.TcpConnectionTimers;
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.EventLoop;
import lombok.extern.slf4j.Slf4j;

import java.util.HashMap;
import java.util.Map;

import static com.github.pangolin.routing.acceptor.tun.net.v2.tcp.internal.TcpConstants.SHUTDOWN_MASK;
import static com.github.pangolin.routing.acceptor.tun.net.v2.tcp.internal.TcpConstants.TCP_INIT_CWND;
import static com.github.pangolin.routing.acceptor.tun.net.v2.tcp.internal.TcpConstants.TCP_MSS_DEFAULT;
import static com.github.pangolin.routing.acceptor.tun.net.v2.tcp.internal.TcpConstants.TCP_NAGLE_OFF;
import static com.github.pangolin.routing.acceptor.tun.net.v2.tcp.internal.TcpSequence.after;
import static com.github.pangolin.routing.acceptor.tun.net.v2.tcp.internal.TcpSequence.before;

@Slf4j
public abstract class TcpMultiplexer {

    private enum CongestionState {
        OPEN,
        RECOVERY,
        LOSS
    }

    @FunctionalInterface
    public interface DataConsumer {
        void onData(FourTuple key, ByteBuf data);
    }

    private static final DataConsumer DROP_DATA = (key, data) -> data.release();

    public static final int TCP_STATE_MASK = 0xF;
    public static final int TCP_ACTION_FIN = 1 << TcpConnectionState.TCP_CLOSED.ordinal();
    public static final int[] NEW_STATE = new int[TcpConnectionState.values().length + 1];

    static {
        NEW_STATE[TcpConnectionState.TCP_ESTABLISHED.ordinal() + 1] = TcpConnectionState.FIN_WAIT_1.ordinal() | TCP_ACTION_FIN;
        NEW_STATE[TcpConnectionState.TCP_SYN_SENT.ordinal() + 1] = TcpConnectionState.TCP_CLOSED.ordinal();
        NEW_STATE[TcpConnectionState.TCP_SYN_RECV.ordinal() + 1] = TcpConnectionState.FIN_WAIT_1.ordinal() | TCP_ACTION_FIN;
        NEW_STATE[TcpConnectionState.FIN_WAIT_1.ordinal() + 1] = TcpConnectionState.FIN_WAIT_1.ordinal();
        NEW_STATE[TcpConnectionState.FIN_WAIT_2.ordinal() + 1] = TcpConnectionState.FIN_WAIT_2.ordinal();
        NEW_STATE[TcpConnectionState.TIME_WAIT.ordinal() + 1] = TcpConnectionState.TCP_CLOSED.ordinal();
        NEW_STATE[TcpConnectionState.TCP_CLOSED.ordinal() + 1] = TcpConnectionState.TCP_CLOSED.ordinal();
        NEW_STATE[TcpConnectionState.CLOSE_WAIT.ordinal() + 1] = TcpConnectionState.LAST_ACK.ordinal() | TCP_ACTION_FIN;
        NEW_STATE[TcpConnectionState.LAST_ACK.ordinal() + 1] = TcpConnectionState.LAST_ACK.ordinal();
        NEW_STATE[TcpConnectionState.TCP_LISTEN.ordinal() + 1] = TcpConnectionState.TCP_CLOSED.ordinal();
        NEW_STATE[TcpConnectionState.CLOSING.ordinal() + 1] = TcpConnectionState.CLOSING.ordinal();
    }

    protected final TcpConfig config;
    protected final TcpHandshakerFactory handshakerFactory;
    protected final DataConsumer dataConsumer;
    protected final Map<FourTuple, tcp_request_sock> synRegistry;
    protected final Map<FourTuple, TcpSock> establishedRegistry;
    protected TcpSock listenSock;

    protected TcpMultiplexer(TcpConfig config) {
        this(config, DROP_DATA);
    }

    protected TcpMultiplexer(TcpConfig config, DataConsumer dataConsumer) {
        this.config = config;
        this.handshakerFactory = new TcpHandshakerFactory(config);
        this.dataConsumer = dataConsumer == null ? DROP_DATA : dataConsumer;
        this.synRegistry = new HashMap<>();
        this.establishedRegistry = new HashMap<>();
        init();
    }

    protected void init() {
        listenSock = init(new TcpSock());
        listenSock.state(TcpConnectionState.TCP_LISTEN);
    }

    protected abstract TcpSock init(TcpSock sk);

    public abstract void tcp_rcv(ChannelHandlerContext net, TcpPacketBuf pkt);

    public abstract void send_reset(ChannelHandlerContext net, TcpPacketBuf pkt, int err);

    public abstract void inet_rtx_syn_ack(ChannelHandlerContext net, TcpSock listenSock, tcp_request_sock req);

    protected abstract tcp_request_sock conn_request(ChannelHandlerContext net, TcpSock listenSock, TcpPacketBuf pkt);

    protected abstract TcpSock syn_recv_sock(ChannelHandlerContext net, TcpSock listenSock, TcpPacketBuf pkt, tcp_request_sock req);

    protected SockCommon __inet_lookup_skb(final TcpPacketBuf pkt) {
        final FourTuple key = FourTuple.of(pkt);
        TcpSock established = establishedRegistry.get(key);
        if (established != null) {
            return established;
        }
        tcp_request_sock req = synRegistry.get(key);
        if (req != null) {
            return req;
        }
        return listenSock;
    }

    public TcpSock tcp_check_req(ChannelHandlerContext net,
                                 TcpSock listenSock,
                                 TcpPacketBuf pkt,
                                 tcp_request_sock req) {
        TcpHandshaker handshaker = req.request();
        if (pkt.isRst()) {
            if (pkt.tcpSeq() == handshaker.rcvNxt()) {
                inet_csk_destroy_sock(req);
                handshaker.abort(net.channel());
            }
            return null;
        }

        if (pkt.isSyn()) {
            if (pkt.tcpSeq() == handshaker.rcvIsn() && handshaker.synAckSent()) {
                inet_rtx_syn_ack(net, listenSock, req);
            }
            return null;
        }

        if (!pkt.isAck()) {
            return null;
        }

        if (pkt.tcpAckNum() != handshaker.sndIsn() + 1) {
            handshaker.sendResetAndAbort(net.channel(), pkt);
            return null;
        }

        return syn_recv_sock(net, listenSock, pkt, req);
    }

    protected void addToHalfQueue(final TcpSock listenSock, final tcp_request_sock req) {
        synRegistry.putIfAbsent(req.fourTuple(), req);
    }

    protected void moveToEstablished(final tcp_request_sock req, final TcpSock sock) {
        synRegistry.remove(req.fourTuple(), req);
        establishedRegistry.put(sock.fourTuple(), sock);
    }

    public void tcp_done(TcpSock tp) {
        if (!tp.hasConnection()) {
            return;
        }
        tp.state(TcpConnectionState.TCP_CLOSED);
        tp.skShutdown(SHUTDOWN_MASK);
        inet_csk_destroy_sock(tp);
    }

    public void inet_csk_destroy_sock(TcpSock sk) {
        if (!sk.hasConnection()) {
            return;
        }
        sk.close();
        establishedRegistry.remove(sk.fourTuple(), sk);
    }

    public void inet_csk_destroy_sock(tcp_request_sock req) {
        req.request().cancelRetransmitTimer();
        synRegistry.remove(req.fourTuple(), req);
    }

    public boolean tcp_close_state(TcpSock sk) {
        if (!sk.hasConnection()) {
            return false;
        }
        int next = NEW_STATE[sk.state().ordinal() + 1];
        int ns = next & TCP_STATE_MASK;
        sk.state(TcpConnectionState.values()[ns]);
        return 0 != (next & TCP_ACTION_FIN);
    }

    public void tcp_time_wait(ChannelHandlerContext ctx, TcpSock tp, TcpConnectionState state) {
        if (!tp.hasConnection()) {
            return;
        }
        tp.state(state);
        if (TcpConnectionState.TIME_WAIT == state) {
            TcpInput.tcp_done(ctx, tp, null);
            return;
        }
    }

    public void consume(final ChannelHandlerContext ctx, final TcpPacketBuf pkt) {
        tcp_rcv(ctx, pkt);
    }

    public boolean write(final FourTuple key, final ByteBuf data) {
        TcpSock sk = establishedRegistry.get(key);
        if (sk == null || !sk.hasConnection() || !sk.state().canSend()) {
            data.release();
            return false;
        }
        sk.tcp_queue_skb(new TcpSegmentEntry(
                data,
                sk.writeSeq(),
                data.readableBytes(),
                (byte) com.github.pangolin.routing.acceptor.tun.net.v2.tcp.internal.TcpConstants.TCPHDR_ACK,
                0L));
        tcp_push_pending_frames(sk);
        return true;
    }

    protected int tcp_ack(TcpSock sk, TcpPacketBuf pkt, int flag) {
        if (!sk.hasConnection() || !pkt.isAck()) {
            return 1;
        }
        final int priorSndUna = sk.sndUna();
        final int priorPktsOut = sk.packetsOut();
        int result = TcpIncomingAckHandler.tcpAck(sk, pkt, flag);
        if (result < 0) {
            return result;
        }
        if (after(sk.sndUna(), priorSndUna)) {
            TcpRetransmitter.INSTANCE.rearmRto(sk);
            if (priorPktsOut > 0) {
                int newlyAcked = Math.max(1, priorPktsOut - sk.packetsOut());
                sk.onAckedByCc(newlyAcked, true);
                sk.resetRtoBackoff();
            }
        }
        return result;
    }

    protected int tcp_data_queue(ChannelHandlerContext ctx, TcpSock sk, TcpPacketBuf pkt) {
        if (!sk.hasConnection()) {
            return 0;
        }
        if (pkt.tcpPayloadLength() > 0 && sk.state().canReceive()) {
            ByteBuf data = TcpDataHandler.INSTANCE.onData(sk, pkt);
            if (data != null) {
                dataConsumer.onData(sk.fourTuple(), data);
            }
            TcpOutput.INSTANCE.tcp_send_ack(sk);
        }

        if (pkt.isFin() && sk.state().canReceive()) {
            sk.rcvNxt(sk.rcvNxt() + 1);
            sk.state(TcpConnectionState.CLOSE_WAIT);
            TcpOutput.INSTANCE.tcp_send_ack(sk);
        }
        tcp_push_pending_frames(sk);
        return 0;
    }

    protected void tcp_push_pending_frames(TcpSock sk) {
        if (sk.hasConnection() && sk.tcpSendHead() != null) {
            TcpOutput.INSTANCE.tcp_write_xmit(sk, sk.mss(), TCP_NAGLE_OFF, 0);
        }
    }

    protected static boolean tcp_sequence_acceptable(TcpSock sk, TcpPacketBuf pkt) {
        int seq = pkt.tcpSeq();
        int segLen = pkt.tcpPayloadLength() + (pkt.isSyn() ? 1 : 0) + (pkt.isFin() ? 1 : 0);
        int endSeq = seq + segLen;
        int rcvWup = sk.rcvWup();
        int rcvWndEnd = sk.rcvNxt() + sk.tcp_receive_window();

        if (before(endSeq, rcvWup)) {
            return false;
        }
        if (after(endSeq, rcvWndEnd)) {
            return !after(seq, rcvWndEnd);
        }
        return true;
    }

    protected static void tcp_init_wl(TcpSock sk, int seq) {
        if (sk.hasConnection()) {
            sk.sndWl1(seq);
        }
    }

    protected static void tcp_init_transfer(TcpSock sk) {
    }

    protected static int tcp_initialize_rcv_mss(TcpSock sk) {
        if (!sk.hasConnection()) {
            return TCP_MSS_DEFAULT;
        }
        int mss = sk.mss();
        int hint = Math.min(mss, sk.rcvWnd() / 2);
        hint = Math.min(hint, TCP_INIT_CWND * mss);
        return Math.max(hint, TCP_MSS_DEFAULT);
    }

    protected abstract static class SockCommon {
        private final FourTuple fourTuple;

        protected SockCommon(FourTuple fourTuple) {
            this.fourTuple = fourTuple;
        }

        public FourTuple fourTuple() {
            return fourTuple;
        }

        public abstract TcpConnectionState state();

        public abstract void state(TcpConnectionState state);
    }

    public static class TcpSock extends SockCommon {
        private TcpConnection conn;
        private Channel channel;
        private TcpSendBuffer sendBuffer;
        private TcpReceiveBuffer receiveBuffer;
        private TcpConnectionTimers timers;
        private final Map<ConnectionKey<?>, Object> attributes = new HashMap<>();
        private TcpConnectionState state;
        private int sndUna;
        private int sndNxt;
        private int writeSeq;
        private int rcvNxt;
        private int sndWnd;
        private int maxWindow;
        private int sndWl1;
        private int rcvWnd;
        private int rcvWup;
        private int rcvMss;
        private int mss;
        private int sndWscale;
        private int rcvWscale;
        private long bytesAcked;
        private int packetsOut;
        private int skShutdown;
        private int ackPending;
        private int skErr;
        private long lastOowAckTimeMs;
        private boolean timestampEnabled;
        private int recentTimestamp;
        private long srttUs;
        private long rttvarUs;
        private int rtoBackoffShift;
        private int cwnd = TcpConstants.TCP_INIT_CWND;
        private int ssthresh = Integer.MAX_VALUE;
        private int dupacks;
        private int caIncrCounter;
        private CongestionState congestionState = CongestionState.OPEN;
        private int highSeq;

        protected TcpSock() {
            this(null, false);
        }

        protected TcpSock(TcpConnection conn) {
            this(conn, true);
        }

        protected TcpSock(TcpConnection conn, boolean initializeExtensions) {
            super(conn == null ? null : conn.fourTuple());
            attach(conn, initializeExtensions);
        }

        protected TcpSock(FourTuple fourTuple) {
            super(fourTuple);
        }

        public static TcpSock from(TcpConnection conn) {
            return new TcpSock(conn, true);
        }

        public static TcpSock view(TcpConnection conn) {
            return new TcpSock(conn, false);
        }

        public static TcpSock createChild(Channel channel,
                                          FourTuple fourTuple,
                                          int sndUna,
                                          int sndNxt,
                                          int rcvNxt,
                                          int sndWnd,
                                          int rcvWnd,
                                          int mss,
                                          int sndWscale,
                                          int rcvWscale) {
            TcpSock sock = new TcpSock(fourTuple);
            sock.channel = channel;
            sock.sendBuffer = new TcpSendBuffer();
            sock.receiveBuffer = new TcpReceiveBuffer(channel.alloc());
            sock.timers = new TcpConnectionTimers();
            sock.state = TcpConnectionState.TCP_SYN_RECV;
            sock.sndUna = sndUna;
            sock.sndNxt = sndNxt;
            sock.writeSeq = sndNxt;
            sock.rcvNxt = rcvNxt;
            sock.sndWnd = sndWnd;
            sock.maxWindow = sndWnd;
            sock.sndWl1 = 0;
            sock.rcvWnd = rcvWnd;
            sock.rcvWup = rcvNxt;
            sock.rcvMss = mss;
            sock.mss = mss;
            sock.sndWscale = sndWscale;
            sock.rcvWscale = rcvWscale;
            sock.initInlineTcpState();
            return sock;
        }

        public TcpConnection connection() {
            return conn;
        }

        public boolean hasConnection() {
            return channel != null && sendBuffer != null && receiveBuffer != null;
        }

        public void attach(TcpConnection conn) {
            attach(conn, true);
        }

        public void attach(TcpConnection conn, boolean initializeExtensions) {
            this.conn = conn;
            this.channel = conn == null ? null : conn.channel();
            this.sendBuffer = conn == null ? null : conn.sendBuffer();
            this.receiveBuffer = conn == null ? null : conn.receiveBuffer();
            this.timers = conn == null ? null : conn.timers();
            this.attributes.clear();
            loadFromConnection(conn);
            if (initializeExtensions || conn == null) {
                initInlineTcpState();
            }
        }

        private void initInlineTcpState() {
            timestampEnabled = false;
            recentTimestamp = 0;
            srttUs = 0L;
            rttvarUs = 0L;
            rtoBackoffShift = 0;
            cwnd = TcpConstants.TCP_INIT_CWND;
            ssthresh = Integer.MAX_VALUE;
            dupacks = 0;
            caIncrCounter = 0;
            congestionState = CongestionState.OPEN;
            highSeq = 0;
        }

        private void loadFromConnection(TcpConnection conn) {
            if (conn == null) {
                return;
            }
            if (channel == null) {
                channel = conn.channel();
            }
            if (sendBuffer == null) {
                sendBuffer = conn.sendBuffer();
            }
            if (receiveBuffer == null) {
                receiveBuffer = conn.receiveBuffer();
            }
            if (timers == null) {
                timers = conn.timers();
            }
            this.state = conn.state();
            this.sndUna = conn.sndUna();
            this.sndNxt = conn.sndNxt();
            this.writeSeq = conn.writeSeq();
            this.rcvNxt = conn.rcvNxt();
            this.sndWnd = conn.sndWnd();
            this.maxWindow = conn.maxWindow();
            this.sndWl1 = conn.sndWl1();
            this.rcvWnd = conn.rcvWnd();
            this.rcvWup = conn.rcvWup();
            this.rcvMss = conn.rcvMss();
            this.mss = conn.mss();
            this.sndWscale = conn.sndWscale();
            this.rcvWscale = conn.rcvWscale();
            this.bytesAcked = conn.bytesAcked();
            this.packetsOut = conn.packetsOut();
            this.skShutdown = conn.skShutdown();
            this.ackPending = conn.ackPending();
            this.skErr = conn.skErr();
            this.lastOowAckTimeMs = conn.lastOowAckTimeMs();
            this.timestampEnabled = conn.timestampEnabled();
            this.recentTimestamp = conn.recentTimestamp();
            this.srttUs = conn.srttUs();
            this.rttvarUs = conn.rttvarUs();
            this.rtoBackoffShift = conn.rtoBackoffShift();
            this.cwnd = conn.cwnd();
            this.ssthresh = conn.ssthresh();
            this.dupacks = conn.dupacks();
            this.caIncrCounter = conn.caIncrCounter();
            this.congestionState = conn.congestionState() == null
                    ? CongestionState.OPEN
                    : CongestionState.valueOf(conn.congestionState());
            this.highSeq = conn.highSeq();
        }

        public Channel channel() {
            return channel;
        }

        public EventLoop eventLoop() {
            return channel == null ? null : channel.eventLoop();
        }

        public int sndUna() {
            return sndUna;
        }

        public int sndNxt() {
            return sndNxt;
        }

        public void sndNxt(int v) {
            this.sndNxt = v;
        }

        public void sndUna(int v) {
            this.sndUna = v;
        }

        public int writeSeq() {
            return writeSeq;
        }

        public void writeSeq(int v) {
            this.writeSeq = v;
        }

        public int rcvNxt() {
            return rcvNxt;
        }

        public void rcvNxt(int v) {
            this.rcvNxt = v;
        }

        public int sndWnd() {
            return sndWnd;
        }

        public void sndWnd(int v) {
            this.sndWnd = v;
            if (Integer.compareUnsigned(v, maxWindow) > 0) {
                this.maxWindow = v;
            }
        }

        public int maxWindow() {
            return maxWindow;
        }

        public void maxWindow(int v) {
            this.maxWindow = v;
        }

        public int packetsOut() {
            return packetsOut;
        }

        public void packetsOut(int v) {
            this.packetsOut = v;
        }

        public int sndWl1() {
            return sndWl1;
        }

        public void sndWl1(int v) {
            this.sndWl1 = v;
        }

        public int rcvWnd() {
            return rcvWnd;
        }

        public void rcvWnd(int v) {
            this.rcvWnd = v;
        }

        public int rcvWup() {
            return rcvWup;
        }

        public void rcvWup(int v) {
            this.rcvWup = v;
        }

        public int rcvMss() {
            return rcvMss;
        }

        public void rcvMss(int v) {
            this.rcvMss = v;
        }

        public int mss() {
            return mss;
        }

        public void mss(int v) {
            this.mss = v;
        }

        public int sndWscale() {
            return sndWscale;
        }

        public void sndWscale(int v) {
            this.sndWscale = v;
        }

        public int rcvWscale() {
            return rcvWscale;
        }

        public void rcvWscale(int v) {
            this.rcvWscale = v;
        }

        public long bytesAcked() {
            return bytesAcked;
        }

        public void bytesAcked(long v) {
            this.bytesAcked = v;
        }

        public int skShutdown() {
            return skShutdown;
        }

        public void skShutdown(int mask) {
            this.skShutdown = mask;
        }

        public void addShutdown(int how) {
            this.skShutdown |= how;
        }

        public boolean hasShutdown(int how) {
            return (this.skShutdown & how) != 0;
        }

        public int ackPending() {
            return ackPending;
        }

        public void addAckPending(int bits) {
            this.ackPending |= bits;
        }

        public void clearAckPending(int bits) {
            this.ackPending &= ~bits;
        }

        public boolean hasAckPending(int bits) {
            return (this.ackPending & bits) != 0;
        }

        public int skErr() {
            return skErr;
        }

        public void skErr(int err) {
            this.skErr = err;
        }

        public long lastOowAckTimeMs() {
            return lastOowAckTimeMs;
        }

        public void lastOowAckTimeMs(long v) {
            this.lastOowAckTimeMs = v;
        }

        public int tcp_receive_window() {
            return Math.max(0, rcvWup + rcvWnd - rcvNxt);
        }

        public int sndUnaUpdate(int ackSeq) {
            if (!after(ackSeq, sndUna)) {
                return 0;
            }
            int delta = ackSeq - sndUna;
            sndUna = ackSeq;
            bytesAcked += delta;
            return delta;
        }

        public int acknowledgeUpTo(int ackSeq) {
            int delta = sndUnaUpdate(ackSeq);
            if (delta > 0 && sendBuffer != null) {
                packetsOut -= sendBuffer.acknowledgeUpTo(ackSeq);
            }
            return delta;
        }

        public void incrementPacketsOut() {
            packetsOut++;
        }

        public TcpSegmentEntry tcpSendHead() {
            return sendBuffer == null ? null : sendBuffer.peekWrite();
        }

        public void tcp_queue_skb(TcpSegmentEntry skb) {
            if (sendBuffer != null) {
                writeSeq = skb.endSeq();
                sendBuffer.enqueue(skb);
            }
        }

        public void cleanRtxQueue(int ackSeq) {
            if (sendBuffer != null) {
                packetsOut -= sendBuffer.acknowledgeUpTo(ackSeq);
            }
        }

        public <T> T getAttr(ConnectionKey<T> key) {
            return (T) attributes.get(key);
        }

        public <T> void setAttr(ConnectionKey<T> key, T value) {
            attributes.put(key, value);
        }

        public void removeAttr(ConnectionKey<?> key) {
            attributes.remove(key);
        }

        public TcpConnectionTimers timers() {
            return timers;
        }

        public TcpSendBuffer sendBuffer() {
            return sendBuffer;
        }

        public TcpReceiveBuffer receiveBuffer() {
            return receiveBuffer;
        }

        public void close() {
            if (timers != null) {
                timers.cancelAll();
            }
            if (sendBuffer != null) {
                sendBuffer.releaseAll();
            }
            if (receiveBuffer != null) {
                receiveBuffer.releaseAll();
            }
        }

        public boolean timestampEnabled() {
            return timestampEnabled;
        }

        public boolean pawsRejected(int tsval) {
            return timestampEnabled && Integer.compareUnsigned(tsval, recentTimestamp) < 0;
        }

        public void updateRecentTimestamp(int tsval) {
            if (timestampEnabled) {
                recentTimestamp = tsval;
            }
        }

        public void addRttSample(long rttUs) {
            if (rttUs < 0) {
                return;
            }
            if (srttUs == 0) {
                srttUs = rttUs;
                rttvarUs = rttUs / 2;
            } else {
                long diff = Math.abs(srttUs - rttUs);
                rttvarUs = (3 * rttvarUs + diff) / 4;
                srttUs = (7 * srttUs + rttUs) / 8;
            }
            rtoBackoffShift = 0;
        }

        public long rtoMs() {
            long baseUs;
            if (srttUs == 0) {
                baseUs = TcpConstants.RTO_INIT_MS * 1_000L;
            } else {
                baseUs = srttUs + Math.max(1_000L, 4 * rttvarUs);
            }
            long rtoMs = (baseUs << rtoBackoffShift) / 1_000L;
            return Math.min(Math.max(rtoMs, TcpConstants.RTO_MIN_MS), TcpConstants.RTO_MAX_MS);
        }

        public void backoffRto() {
            if (rtoBackoffShift < 6) {
                rtoBackoffShift++;
            }
        }

        public void resetRtoBackoff() {
            rtoBackoffShift = 0;
        }

        public void onAckedByCc(int newlyAcked, boolean advanced) {
            if (!advanced) {
                if (++dupacks == 3 && congestionState == CongestionState.OPEN) {
                    ssthresh = Math.max(cwnd / 2, 2);
                    cwnd = ssthresh + 3;
                    highSeq = sndNxt;
                    congestionState = CongestionState.RECOVERY;
                    caIncrCounter = 0;
                    TcpRetransmitter.INSTANCE.retransmit(this);
                } else if (congestionState == CongestionState.RECOVERY) {
                    cwnd++;
                }
                return;
            }

            if (congestionState == CongestionState.RECOVERY && after(sndUna, highSeq)) {
                cwnd = ssthresh;
                congestionState = CongestionState.OPEN;
                caIncrCounter = 0;
            } else if (congestionState == CongestionState.LOSS) {
                congestionState = CongestionState.OPEN;
                caIncrCounter = 0;
            }

            dupacks = 0;
            if (cwnd < ssthresh) {
                cwnd += newlyAcked;
            } else {
                caIncrCounter += newlyAcked;
                if (caIncrCounter >= cwnd) {
                    cwnd++;
                    caIncrCounter = 0;
                }
            }
        }

        public void onTimeoutByCc() {
            ssthresh = Math.max(cwnd / 2, 2);
            cwnd = 1;
            dupacks = 0;
            caIncrCounter = 0;
            congestionState = CongestionState.LOSS;
        }

        public int cwnd() {
            return cwnd;
        }

        @Override
        public TcpConnectionState state() {
            return state;
        }

        @Override
        public void state(TcpConnectionState state) {
            this.state = state;
        }
    }

    protected static final class tcp_request_sock extends SockCommon {
        private final TcpSock listener;
        private final TcpHandshaker request;

        protected tcp_request_sock(FourTuple key, TcpSock listener, TcpHandshaker request) {
            super(key);
            this.listener = listener;
            this.request = request;
        }

        public TcpSock listener() {
            return listener;
        }

        public TcpHandshaker request() {
            return request;
        }

        @Override
        public TcpConnectionState state() {
            return TcpConnectionState.TCP_SYN_RECV;
        }

        @Override
        public void state(TcpConnectionState state) {
        }
    }
}


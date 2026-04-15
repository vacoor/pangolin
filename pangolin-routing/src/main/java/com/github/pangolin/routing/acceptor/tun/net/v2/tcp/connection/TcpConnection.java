package com.github.pangolin.routing.acceptor.tun.net.v2.tcp.connection;

import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.connection.TcpSendBuffer.TcpSegmentEntry;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.ext.cc.CongestionControl;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.ext.cc.NoopCongestionControl;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.ext.loss.LossDetector;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.ext.loss.NoopLossDetector;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.ext.rtt.NoopRttEstimator;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.ext.rtt.RttEstimator;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.ext.timestamp.NoopTimestampExtension;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.ext.timestamp.TcpTimestampExtension;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.internal.TcpConstants;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.internal.TcpSequence;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.timer.TcpConnectionTimers;
import io.netty.channel.Channel;
import io.netty.channel.EventLoop;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Rich domain model for a single TCP connection (RFC 9293).
 * All state is private; accessed only through typed methods.
 *
 * <p>Threading: all field accesses must occur on the connection's assigned Worker EventLoop
 * ({@link #eventLoop()}). No synchronisation is used.
 *
 * <p>Construction: use {@link Builder} to assemble extensions, then call {@link Builder#build()}.
 */
public final class TcpConnection {

    // ── RFC 9293 core state ──────────────────────────────────────────────────
    private TcpConnectionState state;
    private int sndUna;    // SND.UNA — oldest unacknowledged sequence number
    private int sndNxt;    // SND.NXT — next sequence number to send (advanced on transmit)
    private int writeSeq;  // write_seq — next sequence number to be queued (advanced on enqueue, mirrors Linux tp->write_seq)
    private int rcvNxt;    // RCV.NXT — next sequence number expected from peer
    private int sndWnd;    // SND.WND — peer's receive window
    private int maxWindow; // maximum SND.WND ever seen from peer (Linux: tp->max_window)
    private int sndWl1;    // SND.WL1 (linux: tp->snd_wl1) — SEQ of last window-update segment
    private int rcvWnd;    // RCV.WND — our advertised receive window
    private int rcvWup;    // RCV.WUP — receive-window update point (Linux-style)
    private int rcvMss;    // RCV.MSS (linux: icsk_ack.rcv_mss) — effective receive MSS for delayed-ACK
    private int mss;       // Maximum Segment Size (negotiated)
    private int sndWscale; // peer's receive window scale factor
    private int rcvWscale; // our receive window scale factor
    private long bytesAcked;  // cumulative bytes acknowledged (Linux: tp->bytes_acked)
    private int packetsOut;  // segments in flight awaiting ACK (Linux: tp->packets_out)
    /**
     * Linux-style shutdown mask: RCV_SHUTDOWN / SEND_SHUTDOWN
     */
    private int skShutdown;
    /**
     * ACK-pending bitmask — mirrors Linux {@code icsk_ack.pending}.
     * Bits: {@code ACK_SCHED} | {@code ACK_TIMER} | {@code ACK_NOW}
     * (see {@link com.github.pangolin.routing.acceptor.tun.net.v2.tcp.internal.TcpConstants}).
     */
    private int ackPending;
    /**
     * Pending socket error — mirrors Linux {@code sk->sk_err}.
     * Set by {@code TcpInput.tcp_reset} before {@code sk_error_report}; inspectable
     * in {@code channelInactive} handlers to determine why the connection was aborted.
     */
    private int skErr;
    /**
     * Last time we sent an out-of-window challenge/dupack (ms).
     */
    private long lastOowAckTimeMs;

    // ── Netty integration ───────────────────────────────────────────────────
    private final Channel channel;

    // ── RFC extension per-conn state storage ────────────────────────────────
    private final Map<ConnectionKey<?>, Object> attributes = new HashMap<>();

    // ── Buffers ─────────────────────────────────────────────────────────────
    private final TcpSendBuffer sendBuffer;
    private final TcpReceiveBuffer receiveBuffer;

    // ── Pluggable extensions ─────────────────────────────────────────────────
    private final RttEstimator rttEstimator;
    private final CongestionControl congestionControl;
    private final LossDetector lossDetector;
    private final TcpTimestampExtension timestampExt;

    // ── Timer slots ──────────────────────────────────────────────────────────
    private final TcpConnectionTimers timers = new TcpConnectionTimers();

    private TcpConnection(Builder b) {
        this.channel = b.channel;
        this.state = TcpConnectionState.TCP_ESTABLISHED;
        this.sndUna = b.sndUna;
        this.sndNxt = b.sndNxt;
        this.writeSeq = b.sndNxt;  // initially equal to sndNxt — no data queued yet
        this.rcvNxt = b.rcvNxt;
        this.sndWnd = b.sndWnd;
        this.maxWindow = b.sndWnd;  // initialise to the first advertised window
        this.sndWl1 = b.sndWl1;
        this.rcvWnd = b.rcvWnd;
        this.rcvWup = b.rcvWup;
        this.rcvMss = b.mss;    // tcp_initialize_rcv_mss will refine this in ESTABLISHED transition
        this.mss = b.mss;
        this.sndWscale = b.sndWscale;
        this.rcvWscale = b.rcvWscale;
        this.skShutdown = 0;
        this.lastOowAckTimeMs = 0L;
        this.rttEstimator = b.rttEstimator;
        this.congestionControl = b.congestionControl;
        this.lossDetector = b.lossDetector;
        this.timestampExt = b.timestampExt;
        this.sendBuffer = new TcpSendBuffer();
        this.receiveBuffer = new TcpReceiveBuffer(channel.alloc());
    }

    // ── Accessors ────────────────────────────────────────────────────────────

    public Channel channel() {
        return channel;
    }

    public EventLoop eventLoop() {
        return channel.eventLoop();
    }

    public TcpConnectionState state() {
        return state;
    }

    public int sndUna() {
        return sndUna;
    }

    public int sndNxt() {
        return sndNxt;
    }

    public int writeSeq() {
        return writeSeq;
    }

    public int rcvNxt() {
        return rcvNxt;
    }

    public int sndWnd() {
        return sndWnd;
    }

    public int maxWindow() {
        return maxWindow;
    }

    public long bytesAcked() {
        return bytesAcked;
    }

    public int packetsOut() {
        return packetsOut;
    }

    public int sndWl1() {
        return sndWl1;
    }

    public int rcvWnd() {
        return rcvWnd;
    }

    public int rcvWup() {
        return rcvWup;
    }

    public int rcvMss() {
        return rcvMss;
    }

    public int mss() {
        return mss;
    }

    public int sndWscale() {
        return sndWscale;
    }

    public int rcvWscale() {
        return rcvWscale;
    }

    public int skShutdown() {
        return skShutdown;
    }

    public int ackPending() {
        return ackPending;
    }

    public int skErr() {
        return skErr;
    }

    public long lastOowAckTimeMs() {
        return lastOowAckTimeMs;
    }

    /**
     * Available receive window size, mirroring Linux {@code tcp_receive_window()}:
     * <pre>
     *   max(0, rcv_wup + rcv_wnd - rcv_nxt)
     * </pre>
     * {@code rcv_wup} is the sequence number at which the window was last advertised;
     * subtracting {@code rcv_nxt} gives the bytes still available since that advertisement.
     *
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_output.c">tcp_receive_window</a>
     */
    public int tcp_receive_window() {
        return Math.max(0, rcvWup + rcvWnd - rcvNxt);
    }

    // ── State mutators ───────────────────────────────────────────────────────

    public void state(TcpConnectionState s) {
        this.state = s;
    }

    public void sndNxt(int v) {
        this.sndNxt = v;
    }

    public void writeSeq(int v) {
        this.writeSeq = v;
    }

    public void rcvNxt(int v) {
        this.rcvNxt = v;
    }

    public void sndWnd(int v) {
        this.sndWnd = v;
        if (Integer.compareUnsigned(v, maxWindow) > 0) this.maxWindow = v;
    }

    public void sndWl1(int v) {
        this.sndWl1 = v;
    }

    public void rcvWnd(int v) {
        this.rcvWnd = v;
    }

    public void rcvWup(int v) {
        this.rcvWup = v;
    }

    public void rcvMss(int v) {
        this.rcvMss = v;
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

    /**
     * Set one or more {@code ACK_*} bits.
     */
    public void addAckPending(int bits) {
        this.ackPending |= bits;
    }

    /**
     * Clear one or more {@code ACK_*} bits.
     */
    public void clearAckPending(int bits) {
        this.ackPending &= ~bits;
    }

    /**
     * Test whether any of the given {@code ACK_*} bits are set.
     */
    public boolean hasAckPending(int bits) {
        return (this.ackPending & bits) != 0;
    }

    public void skErr(int err) {
        this.skErr = err;
    }

    public void lastOowAckTimeMs(long v) {
        this.lastOowAckTimeMs = v;
    }

    /**
     * Advance SND.UNA to {@code ackSeq} — does <b>not</b> drain the RTX queue.
     * Mirrors Linux {@code tcp_snd_una_update}: updates only {@code snd_una} and
     * {@code bytes_acked}.  RTX-queue cleanup must follow separately via
     * {@link TcpSendBuffer#acknowledgeUpTo(int)}.
     *
     * @return number of bytes newly acknowledged (0 if ackSeq ≤ SND.UNA)
     */
    public int sndUnaUpdate(int ackSeq) {
        if (!TcpSequence.after(ackSeq, sndUna)) {
            return 0;
        }
        int delta = ackSeq - sndUna;
        sndUna = ackSeq;
        bytesAcked += delta;
        return delta;
    }

    /**
     * Advance SND.UNA to {@code ackSeq} and acknowledge RTX-queue entries.
     * Combines {@link #sndUnaUpdate(int)} with {@link #cleanRtxQueue(int)}.
     *
     * @return number of bytes newly acknowledged
     */
    public int acknowledgeUpTo(int ackSeq) {
        int delta = sndUnaUpdate(ackSeq);
        if (delta > 0) {
            cleanRtxQueue(ackSeq);
        }
        return delta;
    }

    /**
     * Increment {@code packets_out} — called each time a segment is placed on the RTX queue
     * (mirrors Linux {@code tp->packets_out++} in the send path).
     */
    public void incrementPacketsOut() {
        packetsOut++;
    }

    /**
     * Returns the first unsent entry in the write queue, or {@code null} if the queue is empty.
     * Mirrors Linux {@code tcp_send_head(sk)} (include/net/tcp.h).
     *
     * @see <a href="https://github.com/torvalds/linux/blob/master/include/net/tcp.h">tcp_send_head</a>
     */
    public TcpSegmentEntry tcpSendHead() {
        return sendBuffer.peekWrite();
    }

    /**
     * Append a pre-built SKB to the write queue and advance {@code write_seq}.
     *
     * <p>Mirrors Linux {@code tcp_queue_skb} (tcp_output.c) exactly:
     * <pre>
     *   WRITE_ONCE(tp->write_seq, TCP_SKB_CB(skb)->end_seq);
     *   tcp_add_write_queue_tail(sk, skb);
     * </pre>
     *
     * <p>The caller is responsible for constructing {@code skb} with the correct
     * {@code startSeq} (= current {@link #writeSeq()}) and {@code tcpFlags} before
     * calling this method — mirroring how Linux callers set {@code TCP_SKB_CB(skb)->seq},
     * {@code ->end_seq}, and {@code ->tcp_flags} before invoking {@code tcp_queue_skb}.
     * RST must never be passed here — RST bypasses the write queue entirely
     * (see {@code tcp_send_reset} / {@code tcp_v4_send_reset} in the kernel).
     *
     * @param skb fully initialised segment entry; ownership transferred to the write queue
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_output.c#L1498">tcp_queue_skb</a>
     */
    public void tcp_queue_skb(TcpSegmentEntry skb) {
        // WRITE_ONCE(tp->write_seq, TCP_SKB_CB(skb)->end_seq)
        writeSeq = skb.endSeq();
        sendBuffer.enqueue(skb);
    }

    /**
     * Drain acknowledged entries from the RTX queue and decrement {@code packets_out}
     * accordingly — mirrors Linux {@code tcp_clean_rtx_queue}'s drain loop and its
     * {@code tp->packets_out -= acked_pcount} bookkeeping.
     *
     * <p>Must be called <em>after</em> RTT sampling so that {@link TcpSendBuffer#peekRtx()}
     * still sees the just-ACKed segment head.
     */
    public void cleanRtxQueue(int ackSeq) {
        packetsOut -= sendBuffer.acknowledgeUpTo(ackSeq);
    }

    // ── Extension attributes ─────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    public <T> T getAttr(ConnectionKey<T> key) {
        return (T) attributes.get(key);
    }

    public <T> void setAttr(ConnectionKey<T> key, T value) {
        attributes.put(key, value);
    }

    public void removeAttr(ConnectionKey<?> key) {
        attributes.remove(key);
    }

    // ── Pluggable extensions ─────────────────────────────────────────────────

    public RttEstimator rttEstimator() {
        return rttEstimator;
    }

    public CongestionControl congestionControl() {
        return congestionControl;
    }

    public LossDetector lossDetector() {
        return lossDetector;
    }

    public TcpTimestampExtension timestampExt() {
        return timestampExt;
    }

    // ── Timers / buffers ─────────────────────────────────────────────────────

    public TcpConnectionTimers timers() {
        return timers;
    }

    public TcpSendBuffer sendBuffer() {
        return sendBuffer;
    }

    public TcpReceiveBuffer receiveBuffer() {
        return receiveBuffer;
    }

    /**
     * Close this connection: cancel all timers and notify extensions.
     * Must be called from the connection's EventLoop.
     * Typically triggered by {@code channelInactive()} in the pipeline handler.
     */
    public void close() {
        timers.cancelAll();
        rttEstimator.onConnectionClosed(this);
        congestionControl.onConnectionClosed(this);
        lossDetector.onConnectionClosed(this);
        timestampExt.onConnectionClosed(this);
        sendBuffer.releaseAll();
        receiveBuffer.releaseAll();
    }

    // ── Builder ──────────────────────────────────────────────────────────────

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private Channel channel;
        private int sndUna;
        private int sndNxt;
        private int rcvNxt;
        private int sndWnd;
        private int sndWl1;
        private int rcvWnd = 65535;
        private int rcvWup;
        private boolean rcvWupSet;
        private int mss = 1460;
        private int sndWscale;
        private int rcvWscale;
        private RttEstimator rttEstimator = NoopRttEstimator.INSTANCE;
        private CongestionControl congestionControl = NoopCongestionControl.INSTANCE;
        private Consumer<TcpConnection> retransmitCallback = c -> {
        };
        private LossDetector lossDetector = NoopLossDetector.INSTANCE;
        private TcpTimestampExtension timestampExt = NoopTimestampExtension.INSTANCE;

        public Builder channel(Channel ch) {
            this.channel = ch;
            return this;
        }

        public Builder sndUna(int v) {
            this.sndUna = v;
            return this;
        }

        public Builder sndNxt(int v) {
            this.sndNxt = v;
            return this;
        }

        public Builder rcvNxt(int v) {
            this.rcvNxt = v;
            return this;
        }

        public Builder sndWnd(int v) {
            this.sndWnd = v;
            return this;
        }

        public Builder sndWl1(int v) {
            this.sndWl1 = v;
            return this;
        }

        public Builder rcvWnd(int v) {
            this.rcvWnd = v;
            return this;
        }

        public Builder rcvWup(int v) {
            this.rcvWup = v;
            this.rcvWupSet = true;
            return this;
        }

        public Builder mss(int v) {
            this.mss = v;
            return this;
        }

        public Builder sndWscale(int v) {
            this.sndWscale = v;
            return this;
        }

        public Builder rcvWscale(int v) {
            this.rcvWscale = v;
            return this;
        }

        public Builder rttEstimator(RttEstimator e) {
            this.rttEstimator = e;
            return this;
        }

        /**
         * Set CC and the retransmit callback together (they are always paired).
         * The CC triggers retransmit via callback; RFC 9293 {@code TcpRetransmitter} executes it.
         */
        public Builder congestionControl(CongestionControl cc,
                                         Consumer<TcpConnection> callback) {
            this.congestionControl = cc;
            this.retransmitCallback = callback;
            return this;
        }

        public Builder lossDetector(LossDetector d) {
            this.lossDetector = d;
            return this;
        }

        public Builder timestampExt(TcpTimestampExtension t) {
            this.timestampExt = t;
            return this;
        }

        public TcpConnection build() {
            if (channel == null) throw new IllegalStateException("channel must be set");
            if (!rcvWupSet) {
                rcvWup = rcvNxt;
            }
            TcpConnection conn = new TcpConnection(this);
            rttEstimator.init(conn);
            congestionControl.init(conn, retransmitCallback);
            lossDetector.init(conn);
            timestampExt.init(conn);
            return conn;
        }
    }
}

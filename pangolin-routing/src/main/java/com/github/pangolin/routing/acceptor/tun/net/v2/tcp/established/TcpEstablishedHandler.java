package com.github.pangolin.routing.acceptor.tun.net.v2.tcp.established;

import com.github.pangolin.routing.acceptor.tun.net.handler.support.TcpPacketBuf;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.close.TcpCloseMachine;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.close.TcpActiveCloseHandler;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.close.TcpPassiveCloseHandler;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.connection.TcpConnection;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.connection.TcpConnectionState;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.core.TcpIncomingAckHandler;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.core.TcpSegmenter;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.internal.TcpConstants;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.internal.TcpSequence;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.timer.TcpTimerScheduler;

import static com.github.pangolin.routing.acceptor.tun.net.v2.tcp.internal.TcpConstants.ACK_NOW;
import static com.github.pangolin.routing.acceptor.tun.net.v2.tcp.internal.TcpConstants.ACK_SCHED;
import static com.github.pangolin.routing.acceptor.tun.net.v2.tcp.internal.TcpConstants.ACK_TIMER;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.channels.ClosedChannelException;

/**
 * ESTABLISHED-state segment processing — RFC 9293 §3.10.7.4.
 *
 * <p>Inbound path ({@link #channelRead}) follows the eight-step procedure from the RFC:
 * <ol>
 *   <li>Sequence number acceptability check (§3.4) + PAWS (RFC 7323 §5)</li>
 *   <li>RST bit — pre-screened with the acceptability test (§3.5.3)</li>
 *   <li>Security — not applicable</li>
 *   <li>SYN bit — rejected by step 1 sequence check; challenge-ACK omitted</li>
 *   <li>ACK field — advance SND.UNA, update window, RFC 5681 CC, RFC 6298 timer</li>
 *   <li>URG bit — not implemented</li>
 *   <li>Segment text — deliver in-order; OFO queue for out-of-order</li>
 *   <li>FIN bit — enter CLOSE_WAIT, ACK immediately</li>
 * </ol>
 *
 * <p>After step 7+8, {@code tcp_data_snd_check} flushes any queued send data whose window
 * was re-opened by this ACK (or by the incoming segment's piggybacked window update).
 *
 * <p>After {@code tcp_data_snd_check}, {@code tcp_ack_snd_check} decides whether to send
 * an ACK immediately or arm the delayed-ACK timer.
 *
 * <p>Outbound writes ({@code ctx.write(ByteBuf)}) are enqueued in {@code TcpSendBuffer}
 * and transmitted by {@link TcpSegmenter}.
 *
 * <p>Runs entirely on the connection's assigned Worker EventLoop.
 */
public final class TcpEstablishedHandler extends ChannelDuplexHandler {

    private static final Logger log = LoggerFactory.getLogger(TcpEstablishedHandler.class);

    private final TcpConnection conn;
    /**
     * ACK-pending bitmask — mirrors Linux {@code icsk_ack.pending}.
     * Bits: {@link TcpConstants#ACK_SCHED} | {@link TcpConstants#ACK_TIMER} | {@link TcpConstants#ACK_NOW}
     */
    private int ackPending = 0;

    public TcpEstablishedHandler(TcpConnection conn) {
        this.conn = conn;
    }

    // ── Lifecycle / inbound ───────────────────────────────────────────────

    /**
     * Insert {@link TcpIncomingAckHandler} immediately before this handler when it is
     * added to the pipeline.  The validator performs the common pre-checks
     * (sequence acceptability + ACK processing) for all inbound segments, analogous to
     * how {@link io.netty.handler.codec.http.websocketx.Utf8FrameValidator} sits before
     * the actual WebSocket handler.
     *
     * <p>The validator is never replaced — only the downstream state handler changes on
     * state transitions (ESTABLISHED → active/passive close).
     */
    @Override
    public void handlerAdded(ChannelHandlerContext ctx) {
        ctx.pipeline().addBefore(ctx.name(), "tcp-segment-validator",
                new TcpIncomingAckHandler(conn, log));
    }

    /**
     *
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_ipv4.c#tcp_v4_do_rcv">tcp_v4_do_rcv</a>
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_input.c#tcp_rcv_state_process">tcp_rcv_state_process</a>
     */
    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        if (!(msg instanceof TcpPacketBuf)) {
            ctx.fireChannelRead(msg);
            return;
        }
        TcpPacketBuf pkt = (TcpPacketBuf) msg;
        try {
            // Validation (preProcess + processAck) is handled upstream by TcpIncomingAckHandler.
            // Arriving here means the segment passed sequence checks, SND.UNA/WND are up-to-date,
            // and the AckResult is stored in conn attr TcpIncomingAckHandler.ACK_RESULT_KEY.

            /* ── tcp_ack 之后的 switch(state)：TCP_SYN_RECV → TCP_ESTABLISHED 状态迁移 ────
             * 对应 Linux tcp_rcv_state_process 中 tcp_ack() 之后的 switch case TCP_SYN_RECV：
             *   tcp_init_wl(tp, TCP_SKB_CB(skb)->seq)
             *   tcp_finish_connect(sk, skb)  → tcp_init_transfer + sk_state = ESTABLISHED
             *   tcp_initialize_rcv_mss(sk) */
            if (conn.state() == TcpConnectionState.TCP_SYN_RECV) {
                tcpInitWl(conn, pkt.tcpSeq());            // tcp_init_wl
                conn.state(TcpConnectionState.TCP_ESTABLISHED);
                tcpInitTransfer(conn);                    // tcp_init_transfer (via tcp_finish_connect)
                conn.rcvMss(tcpInitializeRcvMss(conn));  // tcp_initialize_rcv_mss
                log.debug("[TCP] [SYN_RECV] Final ACK accepted — entering ESTABLISHED");
            }

            tcpDataQueue(ctx, pkt);    // step 7: tcp_data_queue
            tcpFin(ctx, pkt);          // step 8: tcp_fin (Linux: inside tcp_data_queue)
            tcpDataSndCheck();         // tcp_data_snd_check
            tcpAckSndCheck(ctx);       // tcp_ack_snd_check
        } finally {
            // pkt is consumed here (not forwarded to TailContext), so we must release it.
            // This is the terminal consumer for all inbound TcpPacketBuf in ESTABLISHED state.
            pkt.release();
        }
    }

    // ── Outbound ───────────────────────────────────────────────────────────

    /**
     * Intercept application writes: enqueue into send buffer and attempt to transmit.
     */
    @Override
    public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
        if (msg instanceof ByteBuf) {
            if (conn.hasShutdown(TcpConstants.SEND_SHUTDOWN)) {
                ((ByteBuf) msg).release();
                promise.setFailure(new ClosedChannelException());
                return;
            }
            ByteBuf data = (ByteBuf) msg;
            conn.sendBuffer().enqueue(data);
            int sndNxtBefore = conn.sndNxt();
            TcpSegmenter.INSTANCE.sendPending(conn);
            // If sendPending() actually transmitted a segment (sndNxt advanced),
            // the PSH+ACK already carries the acknowledgment:
            //   sync path  — clear ACK_SCHED before tcpAckSndCheck() at end of channelRead fires
            //   async path — clear ACK_TIMER and cancel the running delayed-ACK timer
            if (conn.sndNxt() != sndNxtBefore) {
                if ((ackPending & ACK_TIMER) != 0) {
                    TcpTimerScheduler.INSTANCE.cancelDelayedAck(conn);
                }
                ackPending &= ~(ACK_SCHED | ACK_TIMER);
            }
            promise.setSuccess();
        } else {
            ctx.write(msg, promise);
        }
    }

    /**
     * Initiate active close: send FIN, transition to FIN_WAIT_1.
     */
    @Override
    public void close(ChannelHandlerContext ctx, ChannelPromise promise) {
        if (conn.hasShutdown(TcpConstants.SHUTDOWN_MASK)) {
            ctx.close(promise);
            return;
        }
        log.debug("[TCP] [ESTABLISHED] close() called — initiating active close");
        conn.addShutdown(TcpConstants.SEND_SHUTDOWN);
        conn.state(TcpConnectionState.FIN_WAIT_1);
        // Ensure FIN is sequenced after all queued app data.
        TcpSegmenter.INSTANCE.sendPending(conn);
        TcpSegmenter.INSTANCE.sendFin(conn);
        ctx.pipeline().replace(this, "close", new TcpActiveCloseHandler(conn, promise));
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        conn.close();
        ctx.fireChannelInactive();
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        log.warn("[TCP] [ESTABLISHED] Exception — closing", cause);
        TcpCloseMachine.abortiveClose(ctx, conn, null);
    }

    // ── Data receive / send / ACK — mirrors Linux tcp_data_queue / tcp_data_snd_check / tcp_ack_snd_check ──

    /**
     * Mirrors Linux {@code tcp_data_queue} (tcp_input.c).
     *
     * <p>Delivers in-order payload to the application; buffers out-of-order data in the OFO queue.
     * Also calls {@code tcp_measure_rcv_mss} (Linux does this inside {@code tcp_data_queue}).
     * FIN processing ({@code tcp_fin}) is separated into {@link #tcpFin} to keep RFC 9293
     * step structure explicit.
     *
     * @see <a href="https://elixir.bootlin.com/linux/latest/source/net/ipv4/tcp_input.c#L4939">tcp_data_queue</a>
     */
    private void tcpDataQueue(ChannelHandlerContext ctx, TcpPacketBuf pkt) {
        if (pkt.tcpPayloadLength() <= 0 || !conn.state().canReceive()) return;
        tcpMeasureRcvMss(pkt);     // tcp_measure_rcv_mss: adaptive delayed-ACK granularity
        ackPending |= ACK_SCHED;   // received data — we owe peer an ACK
        TcpDataHandler.INSTANCE.onData(ctx, conn, pkt);
        // write() may be called synchronously from the app handler during onData()
        // (piggybacked response); it clears ACK_SCHED when it sends a PSH+ACK.
    }

    /**
     * Mirrors Linux {@code tcp_fin} (tcp_input.c).
     *
     * <p>In Linux, {@code tcp_fin()} is called from inside {@code tcp_data_queue()} when the
     * incoming skb carries the FIN flag. We expose it as a separate step to keep the RFC 9293
     * eight-step structure readable at the call site.
     *
     * <p>Advances RCV.NXT by 1 (FIN occupies one sequence number), sets RCV_SHUTDOWN,
     * transitions to CLOSE_WAIT, and schedules an immediate ACK.
     *
     * @see <a href="https://elixir.bootlin.com/linux/latest/source/net/ipv4/tcp_input.c#L4239">tcp_fin</a>
     */
    private void tcpFin(ChannelHandlerContext ctx, TcpPacketBuf pkt) {
        if (!pkt.isFin() || !conn.state().canReceive()) return;
        conn.rcvNxt(conn.rcvNxt() + 1);   // FIN consumes one sequence number
        conn.addShutdown(TcpConstants.RCV_SHUTDOWN);
        conn.state(TcpConnectionState.CLOSE_WAIT);
        log.debug("[TCP] [ESTABLISHED] FIN received — entering CLOSE_WAIT");
        ackPending |= ACK_SCHED | ACK_NOW;
        ctx.pipeline().replace(this, "close", new TcpPassiveCloseHandler(conn));
    }

    /**
     * Mirrors Linux {@code tcp_data_snd_check} (tcp_input.c).
     *
     * <p>Called after {@link #tcpDataQueue} + {@link #tcpFin}: by this point both
     * {@code tcp_ack} and the incoming segment's piggybacked window update have been applied,
     * so {@code SND.WND} is at its final value for this receive round.  Flushing here lets the
     * sender consume the newly opened credit immediately.
     *
     * <p>If {@code sendPending()} emits a PSH+ACK, that segment already carries the
     * acknowledgement, so we cancel any running delayed-ACK timer.
     *
     * @see <a href="https://elixir.bootlin.com/linux/latest/source/net/ipv4/tcp_input.c#L5818">tcp_data_snd_check</a>
     */
    private void tcpDataSndCheck() {
        int sndNxtPrior = conn.sndNxt();
        TcpSegmenter.INSTANCE.sendPending(conn);
        if (conn.sndNxt() != sndNxtPrior && (ackPending & ACK_TIMER) != 0) {
            TcpTimerScheduler.INSTANCE.cancelDelayedAck(conn);
            ackPending &= ~ACK_TIMER;
        }
    }

    // ── ACK scheduling — mirrors Linux tcp_ack_snd_check / __tcp_ack_snd_check ──────────────

    /**
     * ACK send decision — mirrors Linux {@code tcp_ack_snd_check} / {@code __tcp_ack_snd_check}.
     *
     * <p>If {@code ACK_SCHED} is clear (a piggybacked PSH+ACK from {@link #write} already
     * acknowledged the data), nothing to do.  Otherwise, priority order:
     * <ol>
     *   <li>{@link TcpConstants#ACK_NOW}: immediate ACK required (FIN received, etc.).</li>
     *   <li>{@code rcv_nxt − rcv_wup > rcv_mss}: "ACK every other full segment" rule
     *       (RFC 9293 §3.8.6.2.2).  If we have accumulated more than one {@code rcv_mss}
     *       worth of unacknowledged data, send an immediate ACK so the sender's 2-ACK clock
     *       keeps ticking and the congestion window can grow.</li>
     *   <li>Default: arm the delayed-ACK timer (RFC 9293 §3.8.6.2, max 40 ms).</li>
     * </ol>
     *
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_input.c#__tcp_ack_snd_check">__tcp_ack_snd_check</a>
     */
    private void tcpAckSndCheck(ChannelHandlerContext ctx) {
        if ((ackPending & ACK_SCHED) == 0) return;   // ACK already sent (piggybacked)
        ackPending &= ~ACK_SCHED;

        if ((ackPending & ACK_NOW) != 0) {
            // Immediate ACK required (e.g. FIN received — RFC 9293 §3.10.7.4)
            if ((ackPending & ACK_TIMER) != 0) {
                TcpTimerScheduler.INSTANCE.cancelDelayedAck(conn);
            }
            ackPending &= ~(ACK_NOW | ACK_TIMER);
            TcpSegmenter.INSTANCE.sendAck(conn);
        } else if (TcpSequence.after(conn.rcvNxt(), conn.rcvWup() + conn.rcvMss())) {
            // __tcp_ack_snd_check: rcv_nxt - rcv_wup > rcv_mss → ACK every other full segment
            if ((ackPending & ACK_TIMER) != 0) {
                TcpTimerScheduler.INSTANCE.cancelDelayedAck(conn);
                ackPending &= ~ACK_TIMER;
            }
            TcpSegmenter.INSTANCE.sendAck(conn);
        } else {
            scheduleDelayedAck(ctx);
        }
    }

    /**
     * Mirrors Linux {@code tcp_init_wl} (tcp_input.c): {@code tp->snd_wl1 = seq}.
     *
     * <p>Called in the post-{@code tcp_ack} {@code switch(state)} block for
     * {@code TCP_SYN_RECV}, matching the order in Linux {@code tcp_rcv_state_process()}:
     * {@code tcp_init_wl} is the first call inside {@code case TCP_SYN_RECV} after {@code tcp_ack}.
     *
     * @see <a href="https://elixir.bootlin.com/linux/latest/source/net/ipv4/tcp_input.c#L3562">tcp_init_wl</a>
     */
    private static void tcpInitWl(TcpConnection conn, int seq) {
        conn.sndWl1(seq);
    }

    /**
     * Mirrors Linux {@code tcp_init_transfer} (tcp_input.c).
     *
     * <p>Linux sub-operations and where the equivalents live in our stack:
     * <ul>
     *   <li>{@code tcp_init_congestion_control()} — {@link com.github.pangolin.routing.acceptor.tun.net.v2.tcp.connection.TcpConnection.Builder#build()}:
     *       {@code congestionControl.init(conn, retransmitCallback)}</li>
     *   <li>{@code tcp_init_buffer_space()} — not applicable; we use a fixed {@code rcvWnd}.</li>
     *   <li>{@code sk_wake_async(POLL_OUT)} — {@link com.github.pangolin.routing.acceptor.tun.net.v2.tcp.pipeline.TcpConnectionChannel#doRegister()}:
     *       sets {@code active = true}; {@code AbstractChannel} fires {@code channelActive()} immediately after.</li>
     *   <li>{@code tp->rcv_wup = tp->rcv_nxt} — {@code Builder.rcvWup(rcvNxt)} in
     *       {@code TcpHandshaker.finishHandshake()} (mirroring {@code tcp_finish_connect}).</li>
     * </ul>
     *
     * @see <a href="https://elixir.bootlin.com/linux/latest/source/net/ipv4/tcp_input.c#L5825">tcp_init_transfer</a>
     */
    private static void tcpInitTransfer(TcpConnection conn) {
        // All sub-operations are performed at construction time or during Netty channel
        // registration — see javadoc above for the exact locations.
    }

    /**
     * Mirrors Linux {@code tcp_initialize_rcv_mss} (inet_connection_sock.c).
     * Computes the initial receive MSS used by {@link #tcpAckSndCheck} to decide whether
     * an immediate ACK is warranted.  Called once at the TCP_SYN_RECV→ESTABLISHED transition.
     *
     * @see <a href="https://elixir.bootlin.com/linux/latest/source/net/ipv4/inet_connection_sock.c#L631">tcp_initialize_rcv_mss</a>
     */
    private static int tcpInitializeRcvMss(TcpConnection conn) {
        int mss  = conn.mss();
        int hint = mss;
        hint = Math.min(hint, conn.rcvWnd() / 2);
        hint = Math.min(hint, TcpConstants.TCP_INIT_CWND * mss);   // TCP_INIT_CWND = 10
        hint = Math.max(hint, TcpConstants.TCP_MSS_DEFAULT);        // floor at 536
        return hint;
    }

    /**
     * Mirrors Linux {@code tcp_measure_rcv_mss} (tcp_input.c).
     * Adaptively updates {@code rcv_mss} based on the incoming segment size.
     * Larger incoming segments → larger {@code rcv_mss} → immediate ACK fires less frequently.
     *
     * @see <a href="https://elixir.bootlin.com/linux/latest/source/net/ipv4/tcp_input.c#L562">tcp_measure_rcv_mss</a>
     */
    private void tcpMeasureRcvMss(TcpPacketBuf pkt) {
        int len = pkt.tcpPayloadLength();
        if (len > conn.rcvMss()) {
            conn.rcvMss(Math.min(len, conn.mss()));   // clamp to advmss (= conn.mss())
        }
    }

    /** Arm the delayed-ACK timer if not already running ({@code ACK_TIMER} not set). */
    private void scheduleDelayedAck(ChannelHandlerContext ctx) {
        if ((ackPending & ACK_TIMER) != 0) return;   // timer already armed
        ackPending |= ACK_TIMER;
        TcpTimerScheduler.INSTANCE.scheduleDelayedAck(conn, TcpConstants.DELAYED_ACK_MS, () -> {
            ackPending &= ~ACK_TIMER;
            if (ctx.channel().isActive()) {
                TcpSegmenter.INSTANCE.sendAck(conn);
            }
        });
    }
}

package com.github.pangolin.routing.acceptor.tun.net.v2.tcp.established;

import com.github.pangolin.routing.acceptor.tun.net.handler.support.TcpPacketBuf;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.close.TcpCloseMachine;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.close.TcpActiveCloseHandler;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.close.TcpPassiveCloseHandler;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.connection.TcpConnection;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.connection.TcpConnectionState;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.connection.TcpSendBuffer.TcpSegmentEntry;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.core.TcpIncomingAckHandler;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.core.TcpInput;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.core.TcpOutput;
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
 * and transmitted by {@link TcpOutput}.
 *
 * <p>Runs entirely on the connection's assigned Worker EventLoop.
 */
public final class TcpEstablishedHandler extends ChannelDuplexHandler {

    private static final Logger log = LoggerFactory.getLogger(TcpEstablishedHandler.class);

    private final TcpConnection conn;

    public TcpEstablishedHandler(TcpConnection conn) {
        this.conn = conn;
    }

    // ── Lifecycle / inbound ───────────────────────────────────────────────

    /**
     * Insert {@link TcpIncomingAckHandler} immediately before this handler when it is
     * added to the pipeline.  The handler internally calls {@code TcpIncomingPreValidator}
     * (non-Netty) for flag gate + {@code tcp_validate_incoming} checks, then performs
     * ACK field processing (step 5).  Analogous to how
     * {@link io.netty.handler.codec.http.websocketx.Utf8FrameValidator} sits before
     * the actual WebSocket handler.
     *
     * <p>The handler is never replaced — only the downstream state handler changes on
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

            tcp_data_queue(ctx, pkt);    // step 7: tcp_data_queue
            tcpFin(ctx, pkt);          // step 8: tcp_fin (Linux: inside tcp_data_queue)
            tcp_data_snd_check();         // tcp_data_snd_check
            tcp_ack_snd_check(ctx);       // tcp_ack_snd_check
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
            // tcp_init_nondata_skb / sk_stream_alloc_skb: build skb with seq pre-assigned
            conn.tcp_queue_skb(new TcpSegmentEntry(data, conn.writeSeq(),
                    data.readableBytes(), (byte) TcpConstants.TCPHDR_ACK, 0L));
            // ACK_SCHED | ACK_TIMER clearing is now handled inside TcpOutput.__tcp_transmit_skb,
            // mirroring Linux __tcp_transmit_skb → tcp_event_ack_sent → inet_csk_clear_xmit_timer.
            TcpOutput.INSTANCE.tcp_write_xmit(conn, conn.mss(), TcpConstants.TCP_NAGLE_OFF, 0);
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
        // Queue FIN at the tail of the write queue and push; mirrors Linux tcp_send_fin().
        TcpOutput.INSTANCE.tcp_send_fin(conn);
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
        TcpInput.tcp_done(ctx, conn, null);
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
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_input.c#L4939">tcp_data_queue</a>
     */
    private void tcp_data_queue(ChannelHandlerContext ctx, TcpPacketBuf pkt) {
        if (pkt.tcpPayloadLength() <= 0 || !conn.state().canReceive()) return;
        tcpMeasureRcvMss(pkt);          // tcp_measure_rcv_mss: adaptive delayed-ACK granularity
        conn.addAckPending(ACK_SCHED);  // received data — we owe peer an ACK
        TcpDataHandler.INSTANCE.onData(ctx, conn, pkt);
        // If write() is called synchronously from the app handler during onData(),
        // tcp_transmit_skb will clear ACK_SCHED via the piggybacked ACK.
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
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_input.c#L4239">tcp_fin</a>
     */
    private void tcpFin(ChannelHandlerContext ctx, TcpPacketBuf pkt) {
        if (!pkt.isFin() || !conn.state().canReceive()) return;
        conn.rcvNxt(conn.rcvNxt() + 1);   // FIN consumes one sequence number
        conn.addShutdown(TcpConstants.RCV_SHUTDOWN);
        conn.state(TcpConnectionState.CLOSE_WAIT);
        log.debug("[TCP] [ESTABLISHED] FIN received — entering CLOSE_WAIT");
        conn.addAckPending(ACK_SCHED | ACK_NOW);
        ctx.pipeline().replace(this, "close", new TcpPassiveCloseHandler(conn));
    }

    /**
     * Mirrors Linux {@code tcp_data_snd_check} (tcp_input.c).
     *
     * <p>Called after {@link #tcp_data_queue} + {@link #tcpFin}: by this point both
     * {@code tcp_ack} and the incoming segment's piggybacked window update have been applied,
     * so {@code SND.WND} is at its final value for this receive round.
     *
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_input.c#L5664">tcp_data_snd_check</a>
     */
    private void tcp_data_snd_check() {
        tcp_push_pending_frames();
        tcp_check_space();
    }

    /**
     * Mirrors Linux {@code tcp_push_pending_frames} (include/net/tcp.h).
     *
     * <p>Guard: skip if the send queue is empty (mirrors {@code if (tcp_send_head(sk))}).
     *
     * @see <a href="https://github.com/torvalds/linux/blob/master/include/net/tcp.h#L2102">tcp_push_pending_frames</a>
     */
    private void tcp_push_pending_frames() {
        if (conn.tcpSendHead() == null) {
            return;
        }
        __tcp_push_pending_frames();
    }

    /**
     * Mirrors Linux {@code __tcp_push_pending_frames} (tcp_output.c).
     *
     * <p>Calls {@link TcpOutput#tcp_write_xmit}.
     * ACK_SCHED | ACK_TIMER clearing and delayed-ACK timer cancellation are performed inside
     * {@link TcpOutput} (mirroring Linux {@code __tcp_transmit_skb →
     * tcp_event_ack_sent → inet_csk_clear_xmit_timer}), so this method requires no ACK
     * bookkeeping of its own — exactly matching the Linux implementation.
     *
     * <p>Linux also calls {@code tcp_check_probe_timer()} when {@code tcp_write_xmit}
     * returns non-zero (send window still zero after the attempt).  Zero-window probing
     * is not yet implemented here.
     *
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_output.c#L3005">__tcp_push_pending_frames</a>
     */
    private void __tcp_push_pending_frames() {
        if (TcpOutput.INSTANCE.tcp_write_xmit(conn, conn.mss(), TcpConstants.TCP_NAGLE_OFF, 0)) {
            // TODO tcp_check_probe_timer: sndWnd closed with nothing in flight → arm persist timer
        }
    }

    /**
     * Mirrors Linux {@code tcp_check_space} (tcp_input.c).
     *
     * <p>Linux: if {@code SOCK_QUEUE_SHRUNK} is set (send buffer shrank due to ACK draining
     * the RTX queue), calls {@code sk_stream_write_space()} to wake any blocked {@code sendmsg()}
     * callers.  Not applicable here: Netty's async write model has no blocked writers to wake.
     *
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_input.c#L5739">tcp_check_space</a>
     */
    private void tcp_check_space() {
        // No-op: Netty async writes are never blocked on sndbuf; there is no sendmsg() to wake.
    }

    // ── ACK scheduling — mirrors Linux tcp_ack_snd_check / __tcp_ack_snd_check ──────────────

    /**
     * Mirrors Linux {@code tcp_ack_snd_check} (tcp_input.c).
     *
     * <p>Guard: if {@code ACK_SCHED} is clear the peer has already been acknowledged
     * (piggybacked ACK sent by {@link #write}); nothing to do.
     * Otherwise delegate to {@link #__tcp_ack_snd_check}.
     *
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_input.c#L5659">tcp_ack_snd_check</a>
     */
    private void tcp_ack_snd_check(ChannelHandlerContext ctx) {
        if (!conn.hasAckPending(ACK_SCHED)) {
            // inet_csk_ack_scheduled: not set — data segment already carried the ACK.
            return;
        }
        __tcp_ack_snd_check(ctx);
    }

    /**
     * Mirrors Linux {@code __tcp_ack_snd_check} (tcp_input.c).
     *
     * <p>Evaluates three "send now" conditions in the same order as Linux:
     * <ol>
     *   <li><b>Full-segment rule</b> ({@code rcv_nxt − rcv_wup > rcv_mss}) — ACK every other
     *       full segment so the sender's 2-ACK clock keeps ticking (RFC 9293 §3.8.6.2.2).
     *       Linux also requires a window sub-condition:
     *       {@code (rcv_nxt − copied_seq < rcvlowat) || (__tcp_select_window ≥ rcv_wnd)}.
     *       In our model data is delivered immediately (no blocking {@code recvmsg}), so
     *       {@code copied_seq ≈ rcv_nxt → rcv_nxt − copied_seq ≈ 0 < sk_rcvlowat (= 1)};
     *       the sub-condition is always satisfied and is omitted.</li>
     *   <li><b>Quickack mode</b> ({@code tcp_in_quickack_mode}) — not implemented, always
     *       {@code false}; see {@link #tcp_in_quickack_mode}.</li>
     *   <li><b>{@code ICSK_ACK_NOW}</b> — protocol state mandates a one-time immediate ACK
     *       (e.g. FIN received in ESTABLISHED, or SYN-RECV final ACK).</li>
     * </ol>
     *
     * <p>If any condition is met: {@code goto send_now} — cancel the delayed-ACK timer,
     * clear all ACK-pending flags, send ACK immediately.
     * Otherwise: {@code tcp_send_delayed_ack} — arm the delayed-ACK timer.
     *
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_input.c#L5611">__tcp_ack_snd_check</a>
     */
    private void __tcp_ack_snd_check(ChannelHandlerContext ctx) {
        /*-
         * send_now conditions (Linux ordering):
         *   1. (rcv_nxt - rcv_wup > rcv_mss) && window_sub_condition
         *   2. tcp_in_quickack_mode
         *   3. icsk_ack.pending & ICSK_ACK_NOW
         */
        if (TcpSequence.after(conn.rcvNxt(), conn.rcvWup() + conn.rcvMss())
                || tcp_in_quickack_mode()
                || conn.hasAckPending(ACK_NOW)) {
            // send_now: tcp_send_ack → tcp_event_ack_sent → clear ICSK_ACK_SCHED|TIMER + cancel timer
            tcp_send_ack_now();
            return;
        }

        // Neither condition met — schedule delayed ACK (≈ tcp_send_delayed_ack)
        tcp_send_delayed_ack(ctx);
    }

    /**
     * Send an immediate pure ACK.
     *
     * <p>Corresponds to the {@code send_now} label in Linux {@code __tcp_ack_snd_check}:
     * simply calls {@code tcp_send_ack(sk)}.
     * {@code ICSK_ACK_SCHED | ICSK_ACK_TIMER} are cleared — and the delayed-ACK timer is
     * cancelled — inside {@link TcpOutput#tcp_send_ack} via {@code tcp_event_ack_sent},
     * exactly as Linux {@code __tcp_transmit_skb} does.
     * {@code ICSK_ACK_NOW} is cleared here, before the call, mirroring the Linux
     * {@code __tcp_ack_snd_check} control-flow where the flag is consumed by the caller.
     */
    private void tcp_send_ack_now() {
        conn.clearAckPending(ACK_NOW);   // consumed by the send_now decision; ACK_SCHED|ACK_TIMER cleared inside tcp_send_ack
        TcpOutput.INSTANCE.tcp_send_ack(conn);
    }

    /**
     * Arm the delayed-ACK timer if not already running.
     *
     * <p>Mirrors Linux {@code tcp_send_delayed_ack} (inet_connection_sock.c):
     * compute the delay, arm {@code ICSK_TIME_DACK}. Here we use the fixed
     * {@link TcpConstants#DELAYED_ACK_MS} (40 ms) — RTT-adaptive shortening is not
     * implemented.
     *
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/inet_connection_sock.c#L945">tcp_send_delayed_ack</a>
     */
    private void tcp_send_delayed_ack(ChannelHandlerContext ctx) {
        if (conn.hasAckPending(ACK_TIMER)) return;   // ICSK_ACK_TIMER already armed
        conn.addAckPending(ACK_TIMER);
        TcpTimerScheduler.INSTANCE.scheduleDelayedAck(conn, TcpConstants.DELAYED_ACK_MS, () -> {
            if (ctx.channel().isActive()) {
                // tcp_send_ack internally calls tcp_event_ack_sent which clears ACK_SCHED|ACK_TIMER
                // and cancels the delayed-ACK timer — no explicit clear needed here.
                TcpOutput.INSTANCE.tcp_send_ack(conn);
            } else {
                // Channel is already closing: ACK will never be sent, so clean up flags manually.
                conn.clearAckPending(ACK_TIMER | ACK_SCHED);
            }
        });
    }

    /**
     * Mirrors Linux {@code tcp_in_quickack_mode} (include/net/inet_connection_sock.h).
     *
     * <p>Not implemented: our stack does not track {@code icsk_ack.quick} (quickack
     * segment counter) or pingpong mode ({@code ICSK_ACK_PINGPONG}).
     * Always returns {@code false} — quickack is never active.
     *
     * <p>TODO: implement {@code icsk_ack.quick} counter: set to 2 × {@code snd_cwnd} on
     * connection setup and after an idle period; decremented by {@code tcp_dec_quickack_mode}
     * on each ACK sent; quickack mode is active while the counter is positive and
     * pingpong is not set.
     *
     * @see <a href="https://github.com/torvalds/linux/blob/master/include/net/inet_connection_sock.h">tcp_in_quickack_mode</a>
     */
    private static boolean tcp_in_quickack_mode() {
        return false;
    }

    /**
     * Mirrors Linux {@code tcp_init_wl} (tcp_input.c): {@code tp->snd_wl1 = seq}.
     *
     * <p>Called in the post-{@code tcp_ack} {@code switch(state)} block for
     * {@code TCP_SYN_RECV}, matching the order in Linux {@code tcp_rcv_state_process()}:
     * {@code tcp_init_wl} is the first call inside {@code case TCP_SYN_RECV} after {@code tcp_ack}.
     *
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_input.c#L3562">tcp_init_wl</a>
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
     *   <li>{@code sk_wake_async(POLL_OUT)} — {@link com.github.pangolin.routing.acceptor.tun.net.v2.tcp.pipeline.TcpSockChannel#doRegister()}:
     *       sets {@code active = true}; {@code AbstractChannel} fires {@code channelActive()} immediately after.</li>
     *   <li>{@code tp->rcv_wup = tp->rcv_nxt} — {@code Builder.rcvWup(rcvNxt)} in
     *       {@code TcpHandshaker.finishHandshake()} (mirroring {@code tcp_finish_connect}).</li>
     * </ul>
     *
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_input.c#L5825">tcp_init_transfer</a>
     */
    private static void tcpInitTransfer(TcpConnection conn) {
        // All sub-operations are performed at construction time or during Netty channel
        // registration — see javadoc above for the exact locations.
    }

    /**
     * Mirrors Linux {@code tcp_initialize_rcv_mss} (inet_connection_sock.c).
     * Computes the initial receive MSS used by {@link #tcp_ack_snd_check} to decide whether
     * an immediate ACK is warranted.  Called once at the TCP_SYN_RECV→ESTABLISHED transition.
     *
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/inet_connection_sock.c#L631">tcp_initialize_rcv_mss</a>
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
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_input.c#L562">tcp_measure_rcv_mss</a>
     */
    private void tcpMeasureRcvMss(TcpPacketBuf pkt) {
        int len = pkt.tcpPayloadLength();
        if (len > conn.rcvMss()) {
            conn.rcvMss(Math.min(len, conn.mss()));   // clamp to advmss (= conn.mss())
        }
    }

}

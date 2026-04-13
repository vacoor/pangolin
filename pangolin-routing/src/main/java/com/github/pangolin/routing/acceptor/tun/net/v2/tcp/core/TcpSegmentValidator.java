package com.github.pangolin.routing.acceptor.tun.net.v2.tcp.core;

import com.github.pangolin.routing.acceptor.tun.net.handler.support.TcpPacketBuf;
import com.github.pangolin.routing.acceptor.tun.net.handler.tcp.util.TcpOptionCodec;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.close.TcpCloseMachine;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.connection.ConnectionKey;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.connection.TcpConnection;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.connection.TcpConnectionState;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.internal.TcpConstants;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.internal.TcpSequence;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import org.slf4j.Logger;

import static com.github.pangolin.routing.acceptor.tun.net.handler.tcp.util.TcpLogUtils.logFormat;
import static com.github.pangolin.routing.acceptor.tun.net.v2.tcp.internal.TcpSequence.after;
import static com.github.pangolin.routing.acceptor.tun.net.v2.tcp.internal.TcpSequence.before;

/**
 * Inbound TCP segment validator — analogous to
 * {@link io.netty.handler.codec.http.websocketx.Utf8FrameValidator}.
 *
 * <p>Placed immediately before the state handler (ESTABLISHED, active/passive close) in the
 * pipeline.  Performs the complete per-segment pre-processing path that every state handler
 * must execute before doing any state-machine work:
 *
 * <ol>
 *   <li>Flag gate — drops segments lacking ACK, RST, or SYN.</li>
 *   <li>Sequence acceptability (RFC 9293 §3.4) + PAWS (RFC 7323 §5) — mirrors Linux
 *       {@code tcp_validate_incoming}: sequence check, RST check, SYN challenge.</li>
 *   <li>RST handling (RFC 9293 §3.5.2 + RFC 5961 §3.2) — abortive close on valid RST.</li>
 *   <li>ACK field processing (RFC 9293 §3.10 step 5) — mirrors Linux {@code tcp_ack}:
 *       future-ACK rejection, SND.UNA advancement, window update, RTT/CC/loss notifications,
 *       and RFC 6298 retransmit timer management.</li>
 * </ol>
 *
 * <p>On success the {@link AckResult} is stored as a per-connection attribute under
 * {@link #ACK_RESULT_KEY} so the downstream state handler can read it without re-running
 * any ACK logic.  On failure the segment is released here; the downstream handler never
 * sees it.
 *
 * <p>The {@link #closePromise(ChannelPromise)} setter allows close-phase handlers to
 * register the promise they need if an RST triggers an abortive close while in that state.
 *
 * <p><b>Lifecycle:</b> inserted into the pipeline by
 * {@code TcpEstablishedHandler.handlerAdded()} immediately before the state handler.
 * Never replaced or removed — only the downstream state handler is swapped on state
 * transitions.
 *
 * <p><b>Threading:</b> one instance per connection; all accesses on the connection's
 * EventLoop.  Not {@code @Sharable}.
 */
public final class TcpSegmentValidator extends ChannelInboundHandlerAdapter {

    // ── AckResult ─────────────────────────────────────────────────────────

    /**
     * Result of step-5 ACK processing, exposed to the downstream state handler.
     * Mirrors the role of Linux's {@code FLAG_*} bitmask in {@code tcp_ack()}.
     */
    public enum AckResult {
        /** Segment carried no ACK flag — ACK processing was skipped. */
        NONE,
        /** ACK was a duplicate or covered already-acknowledged data (SND.UNA did not advance). */
        OLD_OR_DUP,
        /** SND.UNA was advanced — new data acknowledged. */
        NEW_DATA_ACKED
    }

    /**
     * Per-packet {@link AckResult} set during each {@link #channelRead}.
     * Valid only within the same EventLoop turn; downstream handlers must read it immediately.
     */
    public static final ConnectionKey<AckResult> ACK_RESULT_KEY =
            ConnectionKey.of("tcp.segment-validator.ack-result");

    // ── Internal validation result ─────────────────────────────────────────

    /** Outcome of {@link #validateIncoming}. */
    private enum ValidateResult { PASS, DROP, CHALLENGE_ACK, RESET }

    /** Outcome of {@link #checkRst}. */
    private enum RstResult { DROP, RESET, CHALLENGE_ACK }

    // ── State ──────────────────────────────────────────────────────────────

    private final TcpConnection conn;
    private final Logger        log;
    /**
     * Promise to notify if an RST triggers an abortive close.
     * Null during ESTABLISHED (no outstanding close promise).
     * Updated by active/passive close handlers when they obtain their close promise.
     */
    private ChannelPromise closePromise;

    public TcpSegmentValidator(TcpConnection conn, Logger log) {
        this.conn = conn;
        this.log  = log;
    }

    /**
     * Register the promise to notify if an abortive RST-close is triggered.
     * Called by close-phase handlers ({@code TcpActiveCloseHandler.handlerAdded},
     * {@code TcpPassiveCloseHandler.close}) when they obtain their close promise.
     */
    public void closePromise(ChannelPromise p) {
        this.closePromise = p;
    }

    // ── Pipeline handler ───────────────────────────────────────────────────

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        if (!(msg instanceof TcpPacketBuf)) {
            ctx.fireChannelRead(msg);
            return;
        }
        TcpPacketBuf pkt = (TcpPacketBuf) msg;

        // ── Flag gate ────────────────────────────────────────────────────────
        // Mirrors Linux tcp_rcv_state_process: drop segments that are neither ACK, RST, nor SYN.
        if (!pkt.isAck() && !pkt.isRst() && !pkt.isSyn()) {
            log.warn(logFormat("[TCP] [RCV]", pkt, "Invalid TCP flag(!ACK, !RST, !SYN) — dropped"));
            pkt.release();
            return;
        }

        // ── tcp_validate_incoming: PAWS + sequence + RST + SYN (steps 1–4) ──
        ValidateResult vr = validateIncoming(conn, pkt);
        if (vr == ValidateResult.RESET) {
            log.debug("[TCP] [{}] RST accepted (seq==RCV.NXT) — abortive close",
                    conn.state().name());
            TcpCloseMachine.abortiveClose(ctx, conn, closePromise);
            pkt.release();
            return;
        }
        if (vr != ValidateResult.PASS) {
            // DROP or CHALLENGE_ACK: side-effects (OOW ACK, challenge ACK) already sent.
            pkt.release();
            return;
        }

        // ── tcp_ack: step 5 ACK field processing ──────────────────────────
        AckResult ackResult = AckResult.NONE;
        if (pkt.isAck()) {
            int ack = pkt.tcpAckNum();
            // Future ACK: send challenge ACK and drop (mirrors Linux tcp_ack returning -1).
            if (TcpSequence.after(ack, conn.sndNxt())) {
                TcpSegmenter.INSTANCE.sendAck(conn);
                pkt.release();
                return;
            }

            int prevUna = conn.sndUna();
            // Core ACK processing: SND.UNA advance, window update, RTT, CC, loss.
            TcpAckProcessor.INSTANCE.onAck(conn, pkt);

            // RFC 6298 retransmit timer management (mirrors Linux tcp_rearm_rto):
            if (!conn.sendBuffer().hasRtxPending()) {
                // §5.2: all in-flight data acknowledged — cancel retransmit timer.
                TcpRetransmitter.INSTANCE.cancelRetransmit(conn);
            } else if (TcpSequence.after(conn.sndUna(), prevUna)) {
                // §5.3: new data acknowledged but RTX queue still has entries — restart RTO.
                TcpRetransmitter.INSTANCE.scheduleRetransmit(conn);
            }

            ackResult = TcpSequence.after(conn.sndUna(), prevUna)
                    ? AckResult.NEW_DATA_ACKED : AckResult.OLD_OR_DUP;
        }

        // Expose the result to the downstream state handler for this EventLoop turn.
        conn.setAttr(ACK_RESULT_KEY, ackResult);
        ctx.fireChannelRead(msg);   // downstream handler owns the release
    }

    // ── tcp_validate_incoming ──────────────────────────────────────────────

    /**
     * Unified validation path for post-handshake states, aligned with Linux
     * {@code tcp_validate_incoming} ordering:
     * PAWS → sequence check → RST check → SYN challenge.
     *
     * @see <a href="https://elixir.bootlin.com/linux/latest/source/net/ipv4/tcp_input.c#L5870">tcp_validate_incoming</a>
     */
    private ValidateResult validateIncoming(TcpConnection conn, TcpPacketBuf pkt) {
        // PAWS check (RFC 7323 §5) — RST is exempt.
        if (conn.timestampExt().isEnabled(conn)) {
            ByteBuf opts = pkt.tcpOptionsSlice();
            long[] ts = TcpOptionCodec.parseTimestamp(opts);
            if (ts != null && conn.timestampExt().isPawsRejected(conn, (int) ts[0])) {
                if (!pkt.isRst()) {
                    TcpSegmenter.INSTANCE.sendAck(conn);
                    return ValidateResult.DROP;
                }
                // RST is accepted even if it did not pass PAWS — fall through to step 1.
            }
        }

        // Step 1: sequence number acceptability (RFC 9293 §3.4).
        if (!tcpSequence(conn, pkt)) {
            /*
             * RFC 793 p.37: "In all states except SYN-SENT, all reset (RST) segments are
             * validated by checking their SEQ-fields."
             * RFC 793 p.69: "If an incoming segment is not acceptable, an acknowledgment
             * should be sent in reply (unless the RST bit is set, if so drop the segment
             * and return)."
             *
             * Mirror Linux ordering: !rst branch first (handles SYN inside), then RST.
             */
            if (!pkt.isRst()) {
                if (pkt.isSyn()) {
                    // Linux syn_challenge: challenge ACK on invalid-sequence SYN (RFC 5961 §4).
                    TcpSegmenter.INSTANCE.sendAck(conn);
                    return ValidateResult.CHALLENGE_ACK;
                }
                // Rate-limited dupack for out-of-window non-RST/non-SYN segments.
                if (shouldSendOowAck(conn)) {
                    TcpSegmenter.INSTANCE.sendAck(conn);
                }
            } else if (tcpResetCheck(conn, pkt)) {
                // Linux tcp_reset_check: accept bare RST at RCV.NXT - 1 in half-close states.
                return ValidateResult.RESET;
            }
            return ValidateResult.DROP;
        }

        // Step 2: RST handling (RFC 9293 §3.5.2 + RFC 5961 §3.2).
        if (pkt.isRst()) {
            RstResult rr = checkRst(conn, pkt);
            if (rr == RstResult.RESET || tcpResetCheck(conn, pkt)) {
                return ValidateResult.RESET;
            }
            if (rr == RstResult.CHALLENGE_ACK) {
                TcpSegmenter.INSTANCE.sendAck(conn);
                return ValidateResult.CHALLENGE_ACK;
            }
            return ValidateResult.DROP;
        }

        // Step 4: SYN challenge in established/closing states (RFC 5961 §4).
        if (pkt.isSyn()) {
            TcpSegmenter.INSTANCE.sendAck(conn);
            return ValidateResult.CHALLENGE_ACK;
        }
        return ValidateResult.PASS;
    }

    /**
     * RFC 9293 §3.4 — segment acceptability test.
     * Written in the same shape as Linux {@code tcp_sequence()} for easier side-by-side
     * comparison:
     * <pre>
     *   if before(end_seq, rcv_wup)           → old sequence
     *   if after(end_seq, rcv_nxt + rcv_wnd)  → invalid end sequence
     *      and after(seq, rcv_nxt + rcv_wnd)  → invalid sequence
     * </pre>
     *
     * @see <a href="https://elixir.bootlin.com/linux/latest/source/net/ipv4/tcp_input.c#L4394">tcp_sequence</a>
     */
    private static boolean tcpSequence(TcpConnection conn, TcpPacketBuf pkt) {
        int seq    = pkt.tcpSeq();
        int segLen = pkt.tcpPayloadLength()
                   + (pkt.isSyn() ? 1 : 0)
                   + (pkt.isFin() ? 1 : 0);
        int endSeq    = seq + segLen;
        int rcvWup    = conn.rcvWup();
        int rcvNxt    = conn.rcvNxt();
        int rcvWndEnd = rcvNxt + conn.rcvWnd();

        if (before(endSeq, rcvWup)) {
            return false;
        }
        if (after(endSeq, rcvWndEnd)) {
            // Allow FIN to extend one byte beyond the window.
            if (!after(endSeq - (pkt.isFin() ? 1 : 0), rcvWndEnd)) {
                return true;
            }
            if (after(seq, rcvWndEnd)) {
                return false;
            }
        }
        return true;
    }

    /**
     * RFC 9293 §3.5.2 + RFC 5961 §3.2 — RST acceptability test (three-way result).
     *
     * <ul>
     *   <li>{@link RstResult#DROP}          — SEG.SEQ outside receive window.</li>
     *   <li>{@link RstResult#RESET}         — SEG.SEQ == RCV.NXT; valid reset.</li>
     *   <li>{@link RstResult#CHALLENGE_ACK} — SEG.SEQ in window but != RCV.NXT;
     *       blind-RST attack mitigation (RFC 5961 §3.2).</li>
     * </ul>
     */
    private static RstResult checkRst(TcpConnection conn, TcpPacketBuf pkt) {
        int seq    = pkt.tcpSeq();
        int rcvNxt = conn.rcvNxt();
        int rcvWnd = conn.rcvWnd();
        if (rcvWnd == 0) {
            return seq == rcvNxt ? RstResult.RESET : RstResult.DROP;
        }
        if (!TcpSequence.between(rcvNxt, seq, rcvNxt + rcvWnd - 1)) {
            return RstResult.DROP;
        }
        return seq == rcvNxt ? RstResult.RESET : RstResult.CHALLENGE_ACK;
    }

    /**
     * Linux {@code tcp_reset_check}: accept a bare RST whose sequence equals
     * {@code RCV.NXT - 1} while the local side has already sent its FIN.
     *
     * @see <a href="https://elixir.bootlin.com/linux/latest/source/net/ipv4/tcp_input.c#L1749">tcp_reset_check</a>
     */
    private static boolean tcpResetCheck(TcpConnection conn, TcpPacketBuf pkt) {
        if (pkt.tcpSeq() != conn.rcvNxt() - 1) {
            return false;
        }
        TcpConnectionState s = conn.state();
        return s == TcpConnectionState.CLOSE_WAIT
                || s == TcpConnectionState.LAST_ACK
                || s == TcpConnectionState.CLOSING;
    }

    /** Rate-limit out-of-window ACKs to avoid ACK storms. */
    private static boolean shouldSendOowAck(TcpConnection conn) {
        long now  = System.currentTimeMillis();
        long last = conn.lastOowAckTimeMs();
        if (last == 0L || now - last >= TcpConstants.INVALID_ACK_RATELIMIT_MS) {
            conn.lastOowAckTimeMs(now);
            return true;
        }
        return false;
    }
}

package com.github.pangolin.routing.acceptor.tun.net.v2.tcp.core;

import com.github.pangolin.routing.acceptor.tun.net.handler.support.TcpPacketBuf;
import com.github.pangolin.routing.acceptor.tun.net.handler.tcp.sock.TcpSock;
import com.github.pangolin.routing.acceptor.tun.net.handler.tcp.util.TcpOptionCodec;
import com.github.pangolin.routing.acceptor.tun.net.handler.tcp.util.TcpUtils;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.close.TcpCloseMachine;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.connection.ConnectionKey;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.connection.TcpConnection;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.connection.TcpConnectionState;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.internal.TcpConstants;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.internal.TcpSequence;
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import org.slf4j.Logger;

import static com.github.pangolin.routing.acceptor.tun.net.handler.tcp.internal.TcpConstants.HZ;
import static com.github.pangolin.routing.acceptor.tun.net.handler.tcp.util.TcpClock.jiffies;
import static com.github.pangolin.routing.acceptor.tun.net.handler.tcp.util.TcpClock.tcp_jiffies32;
import static com.github.pangolin.routing.acceptor.tun.net.handler.tcp.util.TcpLogUtils.logFormat;
import static com.github.pangolin.routing.acceptor.tun.net.handler.tcp.util.TcpUtils.determineEndSeq;
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
        if (!tcp_validate_incoming(ctx, conn, pkt)) {
            // DROP, CHALLENGE_ACK, or RESET: side-effects already applied inside.
            pkt.release();
            return;
        }

        // ── tcp_ack: step 5 ACK field processing ──────────────────────────
        AckResult ackResult = AckResult.NONE;
        if (pkt.isAck()) {
            int prevUna = conn.sndUna();
            int r = tcp_ack(conn, pkt);
            if (r < 0) {
                if (r == -SKB_DROP_REASON_TCP_ACK_UNSENT_DATA) {
                    // Future ACK: mirrors Linux tcp_rcv_state_process sending challenge ACK
                    // for SKB_DROP_REASON_TCP_ACK_UNSENT_DATA before discarding.
                    TcpSegmenter.INSTANCE.sendAck(conn);
                }
                // All other negative reasons (too-old ACK, etc.): challenge ACK already
                // sent inside tcp_ack when applicable.
                pkt.release();
                return;
            }
            ackResult = TcpSequence.after(conn.sndUna(), prevUna)
                    ? AckResult.NEW_DATA_ACKED : AckResult.OLD_OR_DUP;
        }

        // Expose the result to the downstream state handler for this EventLoop turn.
        conn.setAttr(ACK_RESULT_KEY, ackResult);
        ctx.fireChannelRead(msg);   // downstream handler owns the release
    }

    // ── tcp_ack ────────────────────────────────────────────────────────────

    /**
     * Step-5 ACK field processing, mirroring Linux {@code tcp_ack()}.
     *
     * <p>Return value convention (mirrors Linux integer return):
     * <ul>
     *   <li>{@code 1}  — ACK is valid; caller derives {@link AckResult} from SND.UNA advancement.</li>
     *   <li>{@code 0}  — old/duplicate ACK ({@code ack < SND.UNA}, not a blind injection);
     *       segment may still carry data — caller continues with {@link AckResult#OLD_OR_DUP}.</li>
     *   <li>{@code -}{@link #SKB_DROP_REASON_TCP_TOO_OLD_ACK}  — blind-injection ACK
     *       (RFC 5961 §5.2); challenge ACK already sent inside this method.</li>
     *   <li>{@code -}{@link #SKB_DROP_REASON_TCP_ACK_UNSENT_DATA}  — future ACK
     *       ({@code ack > SND.NXT}); caller must send challenge ACK and discard.</li>
     * </ul>
     *
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_input.c#L4246">tcp_ack</a>
     */
    private int tcp_ack(TcpConnection conn, TcpPacketBuf pkt) {
        final int priorSndUna = conn.sndUna();
        final int ack         = pkt.tcpAckNum();

        // Old ACK: ack before SND.UNA — mirrors Linux "if (before(ack, prior_snd_una))" block.
        if (TcpSequence.before(ack, priorSndUna)) {
            // RFC 5961 §5.2: blind data-injection mitigation — ack is so old it falls outside
            // any plausible window (prior_snd_una - max_window).  Send challenge ACK + drop.
            if (TcpSequence.before(ack, priorSndUna - conn.sndWnd())) {
                TcpSegmenter.INSTANCE.sendAck(conn);
                return -SKB_DROP_REASON_TCP_TOO_OLD_ACK;
            }
            // goto old_ack: ordinary duplicate/retransmitted ACK — let segment through.
            return 0;
        }

        // Future ACK: ack beyond SND.NXT — discard per RFC 793 §3.9.
        if (TcpSequence.after(ack, conn.sndNxt())) {
            return -SKB_DROP_REASON_TCP_ACK_UNSENT_DATA;
        }

        // Core: advance SND.UNA, update send window, RTT sample, CC, loss detection.
        TcpAckProcessor.INSTANCE.onAck(conn, pkt);

        // RFC 6298 retransmit timer management — mirrors Linux tcp_rearm_rto():
        if (!conn.sendBuffer().hasRtxPending()) {
            // §5.2: all outstanding data acknowledged — cancel retransmit timer.
            TcpRetransmitter.INSTANCE.cancelRetransmit(conn);
        } else if (TcpSequence.after(conn.sndUna(), priorSndUna)) {
            // §5.3: partial ACK — new data acknowledged but queue still has entries → restart RTO.
            TcpRetransmitter.INSTANCE.scheduleRetransmit(conn);
        }

        return 1;
    }

    // ── tcp_validate_incoming ──────────────────────────────────────────────

    /**
     * Unified validation path for post-handshake states, aligned with Linux
     * {@code tcp_validate_incoming} ordering:
     * PAWS → sequence check → RST check → SYN challenge.
     *
     * <p>Mirrors the Linux boolean return convention: {@code true} means the segment
     * passed all checks and processing should continue; {@code false} means the segment
     * was discarded (side-effects such as OOW ACK, challenge ACK, or abortive close have
     * already been applied inside this method, just like Linux {@code tcp_reset()} is
     * called from within {@code tcp_validate_incoming}).
     *
     * @return {@code true} if the segment is acceptable; {@code false} to drop it.
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_input.c#L5870">tcp_validate_incoming</a>
     */
    private boolean tcp_validate_incoming(ChannelHandlerContext ctx,
                                          TcpConnection conn, TcpPacketBuf pkt) {
        // PAWS check (RFC 7323 §5) — RST is exempt.
        if (conn.timestampExt().isEnabled(conn)) {
            ByteBuf opts = pkt.tcpOptionsSlice();
            long[] ts = TcpOptionCodec.parseTimestamp(opts);
            if (ts != null && conn.timestampExt().isPawsRejected(conn, (int) ts[0])) {
                if (!pkt.isRst()) {
                    TcpSegmenter.INSTANCE.sendAck(conn);
                    return false;
                }
                // RST is accepted even if it did not pass PAWS — fall through to step 1.
            }
        }

        // Step 1: sequence number acceptability (RFC 9293 §3.4).
        final int reason = tcp_sequence(conn, pkt);
        if (reason != SKB_NOT_DROPPED_YET) {
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
                    tcp_send_challenge_ack(conn);
                    return discard(conn, pkt, SKB_DROP_REASON_TCP_INVALID_SYN);
                }
                // Rate-limited dupack for out-of-window non-RST/non-SYN segments.
                if (tcp_oow_rate_limited(conn, pkt, conn.lastOowAckTimeMs())) {
                    // FIXME tcp_send_dupack
                    TcpSegmenter.INSTANCE.sendAck(conn);
                }
            } else if (tcp_reset_check(conn, pkt)) {
                // Linux tcp_reset_check: accept bare RST at RCV.NXT - 1 in half-close states.
                tcp_reset(ctx, conn);
            }
            return discard(conn, pkt, reason);
        }

        // Step 2: RST handling (RFC 9293 §3.5.2 + RFC 5961 §3.2).
        if (pkt.isRst()) {

            /*-
             * RFC 5961 3.2 (extend to match against (RCV.NXT - 1) after a  FIN and SACK too if available):
             * If seq num matches RCV.NXT or (RCV.NXT - 1) after a FIN, or the right-most SACK block,
             * then
             *     RESET the connection
             * else
             *     Send a challenge ACK
             */
            if (pkt.tcpSeq() == conn.rcvNxt() || tcp_reset_check(conn, pkt)) {
                tcp_reset(ctx, conn);
                return false;
            }

            // TODO SACK

            tcp_send_challenge_ack(conn);
            return discard(conn, pkt, SKB_DROP_REASON_TCP_RESET);
        }

        // Step 4: SYN challenge in established/closing states (RFC 5961 §4).
        if (pkt.isSyn()) {
            int seq = pkt.tcpSeq();
            int endSeq = determineEndSeq(pkt);
            if (conn.state() == TcpConnectionState.TCP_SYN_RECV
                    // && sk->sk_socket
                    && pkt.isAck()
                    && seq + 1 == endSeq
                    && seq + 1 == conn.rcvNxt()
                    && pkt.tcpAckNum() == conn.sndNxt()) {
                return true;
            }

            tcp_send_challenge_ack(conn);
            return discard(conn, pkt, SKB_DROP_REASON_TCP_INVALID_SYN);
        }

        return true;
    }

    private boolean discard(TcpConnection tp, TcpPacketBuf pkt, int reason) {
        // TODO log reason
        return false;
    }

    private int tcp_disordered_ack_check(final TcpConnection tp, final TcpPacketBuf pkt) {
        int reason = SKB_DROP_REASON_TCP_RFC7323_PAWS;
        int seq = pkt.tcpSeq();
        int ack = pkt.tcpAckNum();

        /* 1. Is this not a pure ACK ? */
        if (!pkt.isAck() || seq != determineEndSeq(pkt)) {
            return reason;
        }

        /* 2. Is its sequence not the expected one ? */
        if (seq != tp.rcvNxt()) {
            return TcpUtils.before(seq, tp.rcvNxt()) ? SKB_DROP_REASON_TCP_RFC7323_PAWS_ACK : reason;
        }

        /* 3. Is this not a duplicate ACK ? */
        if (ack != tp.sndUna()) {
            return reason;
        }

        /* 4. Is this updating the window ? */
//        if (tcp_may_update_window(tp, ack, seq, th.getWindowAsInt() << tp.rx_opt.snd_wscale)) {
//            return reason;
//        }
        /* 5. Is this not in the replay window ? */
//        if ((s32)(tp->rx_opt.ts_recent - tp->rx_opt.rcv_tsval) > tcp_tsval_replay(sk)) {
//            return reason;
//        }
        return SKB_NOT_DROPPED_YET;
    }


    // ── errno constants used by tcp_reset ────────────────────────────────
    /** Linux {@code ECONNRESET} (104) — connection reset by peer. */
    private static final int ECONNRESET   = 104;
    /** Linux {@code ECONNREFUSED} (111) — connection refused; set when RST arrives in SYN_SENT. */
    private static final int ECONNREFUSED = 111;
    /** Linux {@code EPIPE} (32) — broken pipe; set when RST arrives in CLOSE_WAIT. */
    private static final int EPIPE        = 32;

    /**
     * Mirrors Linux {@code tcp_reset()}: resolve the socket error code, report it,
     * then trigger abortive close (analogous to {@code tcp_done()}).
     *
     * <ul>
     *   <li>{@code TCP_SYN_SENT}  → {@code ECONNREFUSED} (peer rejected active open)</li>
     *   <li>{@code CLOSE_WAIT}    → {@code EPIPE} (app still writing after remote FIN + RST)</li>
     *   <li>{@code TCP_CLOSED}    → skip (already closed, nothing to do)</li>
     *   <li>default               → {@code ECONNRESET}</li>
     * </ul>
     *
     * <p>Called from within {@link #tcp_validate_incoming} so the caller only inspects
     * the boolean return value — mirroring how Linux calls {@code tcp_reset()} internally
     * before returning {@code false}.
     *
     * @see <a href="https://elixir.bootlin.com/linux/latest/source/net/ipv4/tcp_input.c">tcp_reset</a>
     */
    private void tcp_reset(ChannelHandlerContext ctx, TcpConnection conn) {
        final int err;
        switch (conn.state()) {
            case TCP_SYN_SENT: err = ECONNREFUSED; break;
            case CLOSE_WAIT:   err = EPIPE;        break;
            case TCP_CLOSED:   return;             // already closed — skip
            default:           err = ECONNRESET;   break;
        }
        log.debug("[TCP] [{}] RST — err={}", conn.state().name(), errName(err));
        TcpCloseMachine.abortiveClose(ctx, conn, closePromise);
    }

    private static String errName(int err) {
        switch (err) {
            case ECONNREFUSED: return "ECONNREFUSED";
            case EPIPE:        return "EPIPE";
            default:           return "ECONNRESET";
        }
    }

    // ── skb_drop_reason constants ──────────────────────────────────────────
    // Mirrors Linux enum skb_drop_reason (include/net/dropreason-core.h).
    // Values sourced from TcpDropReason (handler/tcp/internal).

    /** Packet accepted — do not drop. */
    private static final int SKB_NOT_DROPPED_YET                    =  0;
    /** ACK field acknowledges data we never sent. */
    private static final int SKB_DROP_REASON_TCP_ACK_UNSENT_DATA    =  9;
    /** ACK is old but within plausible window (old_ack path). */
    private static final int SKB_DROP_REASON_TCP_OLD_ACK            = 10;
    /** ACK is too old — outside any plausible window (blind injection). */
    private static final int SKB_DROP_REASON_TCP_TOO_OLD_ACK        = 11;
    /** PAWS check failed on a non-ACK segment (RFC 7323 §5). */
    private static final int SKB_DROP_REASON_TCP_RFC7323_PAWS       = 36;
    /** PAWS check failed on a pure ACK (disordered-ACK exemption path). */
    private static final int SKB_DROP_REASON_TCP_RFC7323_PAWS_ACK   = 37;
    /** SEQ is before RCV.WUP — segment is fully in the past. */
    private static final int SKB_DROP_REASON_TCP_OLD_SEQUENCE       = 41;
    /** SEQ/end_seq is beyond the current receive window. */
    private static final int SKB_DROP_REASON_TCP_INVALID_SEQUENCE   = 42;
    /** SYN received in an established/closing state — challenge-ACK issued. */
    private static final int SKB_DROP_REASON_TCP_INVALID_SYN        = 46;
    /** Valid RST received — connection aborted. */
    private static final int SKB_DROP_REASON_TCP_RESET               =  2;



    /**
     * RFC 9293 §3.4 — segment acceptability test.
     * Written in the same shape as Linux {@code tcp_sequence()} for easier side-by-side
     * comparison:
     * <pre>
     *   if before(end_seq, rcv_wup)           → SKB_DROP_REASON_TCP_OLD_SEQUENCE
     *   if after(end_seq, rcv_nxt + rcv_wnd)  → invalid end sequence
     *      and after(seq, rcv_nxt + rcv_wnd)  → SKB_DROP_REASON_TCP_INVALID_SEQUENCE
     * </pre>
     *
     * @return {@link #SKB_NOT_DROPPED_YET} (0) if the segment is acceptable;
     *         a non-zero {@code skb_drop_reason} constant otherwise.
     * @see <a href="https://elixir.bootlin.com/linux/latest/source/net/ipv4/tcp_input.c#L4394">tcp_sequence</a>
     */
    private static int tcp_sequence(TcpConnection conn, TcpPacketBuf pkt) {
        int seq    = pkt.tcpSeq();
        int segLen = pkt.tcpPayloadLength()
                   + (pkt.isSyn() ? 1 : 0)
                   + (pkt.isFin() ? 1 : 0);
        int endSeq = seq + segLen;
        int rcvWup    = conn.rcvWup();
        int rcvNxt    = conn.rcvNxt();
        int rcvWndEnd = rcvNxt + conn.tcp_receive_window();

        if (before(endSeq, rcvWup)) {
            return SKB_DROP_REASON_TCP_OLD_SEQUENCE;
        }
        if (after(endSeq, rcvWndEnd)) {
            // Allow FIN to extend one byte beyond the window.
            if (!after(endSeq - (pkt.isFin() ? 1 : 0), rcvWndEnd)) {
                return SKB_NOT_DROPPED_YET;
            }
            if (after(seq, rcvWndEnd)) {
                return SKB_DROP_REASON_TCP_INVALID_SEQUENCE;
            }
        }
        return SKB_NOT_DROPPED_YET;
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
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_input.c#L6329">tcp_reset_check</a>
     */
    private static boolean tcp_reset_check(TcpConnection conn, TcpPacketBuf pkt) {
        if (pkt.tcpSeq() != conn.rcvNxt() - 1) {
            return false;
        }
        TcpConnectionState s = conn.state();
        return s == TcpConnectionState.CLOSE_WAIT
                || s == TcpConnectionState.LAST_ACK
                || s == TcpConnectionState.CLOSING;
    }

    /** Rate-limit out-of-window ACKs to avoid ACK storms. */
    private static boolean tcp_oow_rate_limited(TcpConnection conn, final TcpPacketBuf pkt, final long last_oow_ack_time) {
        /* Data packets without SYNs are not likely part of an ACK loop. */
        if (pkt.tcpSeq() != determineEndSeq(pkt) && !pkt.isSyn()) {
            return false;
        }
        return __tcp_oow_rate_limited(conn, last_oow_ack_time);
    }

    private static boolean __tcp_oow_rate_limited(TcpConnection conn, long last_oow_ack_time) {
        if (0 != last_oow_ack_time) {
            final long elapsed = tcp_jiffies32() - last_oow_ack_time;
            if (0 <= elapsed && elapsed < TcpConstants.INVALID_ACK_RATELIMIT_MS) {
                return true;/* rate-limited: don't send yet! */
            }
        }

        conn.lastOowAckTimeMs(tcp_jiffies32());

        return false;    /* not rate-limited: go ahead, send dupack now! */
    }

    /**
     * RFC 5961 7 [ACK Throttling]
     *
     * @param tp
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_input.c#L3649">tcp_send_challenge_ack</a>
     */
    private void tcp_send_challenge_ack(final TcpConnection tp) {
        /* First check our per-socket dupack rate limit. */
        if (__tcp_oow_rate_limited(tp, tp.lastOowAckTimeMs())) {
            return;
        }

        TcpSegmenter.INSTANCE.sendAck(conn);
        /*
        int ack_limit = tp.ipv4_sysctl_tcp_challenge_ack_limit;
        if (ack_limit == Integer.MAX_VALUE) {
            output.tcp_send_ack(net, tp);
            return;
        }
         */

        /* Then check host-wide RFC 5961 rate limit. */
        /*
        final long now = jiffies() / HZ;
        if (now != tp.ipv4_tcp_challenge_timestamp) {
            int half = (ack_limit + 1) >> 1;
            tp.ipv4_tcp_challenge_timestamp = now;
            tp.ipv4_tcp_challenge_count = get_random_u32_inclusive(half, ack_limit + half - 1);
        }
        int count = tp.ipv4_tcp_challenge_count;
        if (count > 0) {
            tp.ipv4_tcp_challenge_count -= 1;
            output.tcp_send_ack(net, tp);
        }
        */
    }

}

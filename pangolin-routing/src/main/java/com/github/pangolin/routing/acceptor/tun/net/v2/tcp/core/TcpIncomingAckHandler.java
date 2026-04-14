package com.github.pangolin.routing.acceptor.tun.net.v2.tcp.core;

import com.github.pangolin.routing.acceptor.tun.net.handler.support.TcpPacketBuf;
import com.github.pangolin.routing.acceptor.tun.net.handler.tcp.util.TcpUtils;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.connection.ConnectionKey;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.connection.TcpConnection;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.connection.TcpConnectionState;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.internal.TcpSequence;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import org.slf4j.Logger;

import static com.github.pangolin.routing.acceptor.tun.net.handler.tcp.util.TcpLogUtils.logFormat;
import static com.github.pangolin.routing.acceptor.tun.net.handler.tcp.util.TcpUtils.determineEndSeq;
import static com.github.pangolin.routing.acceptor.tun.net.v2.tcp.core.SkbDropReasonConstants.*;
import static com.github.pangolin.routing.acceptor.tun.net.v2.tcp.core.TcpOutSupport.*;

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
public final class TcpIncomingAckHandler extends ChannelInboundHandlerAdapter {

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
    private final TcpIncomingValidator incomingValidator;

    /**
     * Promise to notify if an RST triggers an abortive close.
     * Null during ESTABLISHED (no outstanding close promise).
     * Updated by active/passive close handlers when they obtain their close promise.
     */
    private ChannelPromise closePromise;

    public TcpIncomingAckHandler(TcpConnection conn, Logger log) {
        this.conn = conn;
        this.log  = log;
        this.incomingValidator = new TcpIncomingValidator(conn);
    }

    /**
     * Register the promise to notify if an abortive RST-close is triggered.
     * Called by close-phase handlers ({@code TcpActiveCloseHandler.handlerAdded},
     * {@code TcpPassiveCloseHandler.close}) when they obtain their close promise.
     */
    public void closePromise(ChannelPromise p) {
        this.closePromise = p;
        this.incomingValidator.closePromise(p);
    }

    // ── Pipeline handler ───────────────────────────────────────────────────


    @Override
    public void handlerAdded(ChannelHandlerContext ctx) throws Exception {
        ctx.pipeline().addBefore(ctx.name(), "tcp-incoming-validator", incomingValidator);
    }

    /**
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_input.c#L6910">tcp_rcv_state_process</a>
     */
    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        if (!(msg instanceof TcpPacketBuf)) {
            ctx.fireChannelRead(msg);
            return;
        }
        TcpPacketBuf pkt = (TcpPacketBuf) msg;

        // ── tcp_ack: step 5 ACK field processing ──────────────────────────
        int reason = tcp_ack(conn, pkt, FLAG_SLOWPATH | FLAG_UPDATE_TS_RECENT | FLAG_NO_CHALLENGE_ACK);
        if (reason <= 0) {
            if (TcpConnectionState.TCP_SYN_RECV.equals(conn.state())) {
                // FIXME send one RST
                // tcp_v4_send_reset
                return;
            }
            /* accept old ack during closing */
            if (reason < 0) {
                tcp_send_challenge_ack(conn, false);
                reason = -reason;
                discard(conn, pkt, reason);
                return;
            }
        }

        ctx.fireChannelRead(msg);   // downstream handler owns the release
    }

    private void discard(TcpConnection conn, TcpPacketBuf pkt, int reason) {
        // TODO log it.
    }

    // ── tcp_ack ────────────────────────────────────────────────────────────
    /**
     * Do not skip RFC checks for window update.
     *
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_input.c#L97">FLAG_SLOWPATH</a>
     */
    static final int FLAG_SLOWPATH = 0x100;

    /**
     * Snd_una was changed (!= FLAG_DATA_ACKED).
     *
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_input.c#L97">FLAG_SND_UNA_ADVANCED</a>
     */
    private static final int FLAG_SND_UNA_ADVANCED = 0x400;

    static final int FLAG_UPDATE_TS_RECENT = 0x4000;

    static final int FLAG_NO_CHALLENGE_ACK = 0x8000;

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
    private int tcp_ack(TcpConnection conn, TcpPacketBuf pkt, int flags) {
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




}

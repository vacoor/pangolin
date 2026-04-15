package com.github.pangolin.routing.acceptor.tun.net.v2.tcp.core;

import com.github.pangolin.routing.acceptor.tun.net.handler.support.TcpPacketBuf;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.connection.ConnectionKey;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.connection.TcpConnection;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.connection.TcpConnectionState;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.connection.TcpSendBuffer.TcpSegmentEntry;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.ng.TcpIncomingPreValidator;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import org.slf4j.Logger;

import static com.github.pangolin.routing.acceptor.tun.net.handler.tcp.util.TcpUtils.determineEndSeq;
import static com.github.pangolin.routing.acceptor.tun.net.v2.tcp.core.SkbDropReasonConstants.*;

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

    // ── State ──────────────────────────────────────────────────────────────

    private final TcpConnection conn;
    private final Logger        log;
    private final TcpIncomingPreValidator incomingValidator;

    /**
     * Promise to notify if an RST triggers an abortive close.
     * Null during ESTABLISHED (no outstanding close promise).
     * Updated by active/passive close handlers when they obtain their close promise.
     */
    private ChannelPromise closePromise;

    public TcpIncomingAckHandler(TcpConnection conn, Logger log) {
        this.conn = conn;
        this.log  = log;
        this.incomingValidator = new TcpIncomingPreValidator(conn);
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

        // ── tcp_validate_incoming: flag gate + PAWS + sequence + RST + SYN ──
        // 校验失败时 incomingValidator 已 release pkt，直接返回即可。
        if (!incomingValidator.validate(ctx, pkt)) {
            return;
        }

        // ── tcp_ack: step 5 ACK field processing ──────────────────────────
        final int priorSndUna = conn.sndUna();
        int reason = tcp_ack(conn, pkt, FLAG_SLOWPATH | FLAG_UPDATE_TS_RECENT | FLAG_NO_CHALLENGE_ACK);
        if (reason <= 0) {
            if (TcpConnectionState.TCP_SYN_RECV.equals(conn.state())) {
                // FIXME send one RST
                // tcp_v4_send_reset
                return;
            }
            /* accept old ack during closing */
            if (reason < 0) {
                TcpOutput.INSTANCE.tcp_send_challenge_ack(conn, false);
                reason = -reason;
                discard(conn, pkt, reason);
                return;
            }
        }

        // RFC 6298 §5.2/§5.3: rearm or cancel RTO when SND.UNA advances.
        // Mirrors Linux tcp_rearm_rto(), called here rather than inside tcp_ack().
        if (after(conn.sndUna(), priorSndUna)) {
            TcpRetransmitter.INSTANCE.rearmRto(conn);
        }

        ctx.fireChannelRead(msg);   // downstream handler owns the release
    }

    private void discard(TcpConnection conn, TcpPacketBuf pkt, int reason) {
        // TODO log it.
    }

    // ── tcp_ack flags (mirrors Linux net/ipv4/tcp_input.c flag bitmask) ───
    /** Incoming frame contained data.
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_input.c#L97">FLAG_DATA</a> */
    private static final int FLAG_DATA            = 0x01;

    /** Incoming ACK was a window update.
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_input.c#L97">FLAG_WIN_UPDATE</a> */
    private static final int FLAG_WIN_UPDATE      = 0x02;

    /** Do not skip RFC checks for window update.
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_input.c#L97">FLAG_SLOWPATH</a> */
    static final int FLAG_SLOWPATH                = 0x100;

    /** SND.UNA was changed (!= FLAG_DATA_ACKED).
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_input.c#L97">FLAG_SND_UNA_ADVANCED</a> */
    private static final int FLAG_SND_UNA_ADVANCED = 0x400;

    static final int FLAG_UPDATE_TS_RECENT        = 0x4000;
    static final int FLAG_NO_CHALLENGE_ACK        = 0x8000;

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
        final int priorSndUna  = conn.sndUna();
        final int priorPktsOut = conn.packetsOut();                 // ≈ prior_packets_out
        final int ackSeq       = pkt.tcpSeq();                      // ≈ ack_seq
        final int ack          = pkt.tcpAckNum();

        // Old ACK: mirrors Linux "if (before(ack, prior_snd_una))" block.
        if (before(ack, priorSndUna)) {
            // RFC 5961 §5.2: blind data-injection mitigation — ack is so old it falls outside
            // any plausible window (prior_snd_una - max_window).  Send challenge ACK + drop.
            // Use min(max_window, bytes_acked) to match Linux: bytes_acked caps the bound
            // at the start of the connection before max_window stabilises.
            final int maxWindow = (int) Math.min(conn.maxWindow(), conn.bytesAcked());
            if (before(ack, priorSndUna - maxWindow)) {
                if (0 == (flags & FLAG_NO_CHALLENGE_ACK)) {
                    TcpOutput.INSTANCE.tcp_send_challenge_ack(conn, false);
                }
                return -SKB_DROP_REASON_TCP_TOO_OLD_ACK;
            }
            // goto old_ack: ordinary duplicate/retransmitted ACK — let segment through.
            return 0;
        }

        // Future ACK: ack beyond SND.NXT — discard per RFC 793 §3.9.
        if (after(ack, conn.sndNxt())) {
            return -SKB_DROP_REASON_TCP_ACK_UNSENT_DATA;
        }

        // Mirrors Linux FLAG_SND_UNA_ADVANCED: set before any state mutation so the
        // fast/slow path selector and CC below see a consistent value.
        if (after(ack, priorSndUna)) {
            flags |= FLAG_SND_UNA_ADVANCED;
        }

        // ts_recent update (RFC 7323 §5.3) — after confirming segment is in-window.
        if (0 != (flags & FLAG_UPDATE_TS_RECENT)) {
            conn.timestampExt().updateRecent(conn, ackSeq);    // ≈ tcp_replace_ts_recent
        }

        // Window update + SND.WL1 maintenance: fast path vs slow path.
        // Condition mirrors Linux: (flag & (FLAG_SLOWPATH|FLAG_SND_UNA_ADVANCED)) == FLAG_SND_UNA_ADVANCED
        if ((flags & (FLAG_SLOWPATH | FLAG_SND_UNA_ADVANCED)) == FLAG_SND_UNA_ADVANCED) {
            // Fast path: pure forward advance — window constant, no may_update_window check.
            conn.sndWl1(ackSeq);                                // ≈ tcp_update_wl
            flags |= FLAG_WIN_UPDATE;
        } else {
            // Slow path: full RFC 9293 window acceptability check.
            if (ackSeq != determineEndSeq(pkt)) {
                flags |= FLAG_DATA;                             // segment carries data
            }
            flags |= tcp_ack_update_window(conn, pkt, 0 != (flags & FLAG_SND_UNA_ADVANCED));  // ≈ tcp_ack_update_window
        }

        // Advance SND.UNA only — does NOT drain the RTX queue.
        // Mirrors Linux tcp_snd_una_update(); RTX cleanup follows separately below.
        conn.sndUnaUpdate(ack);

        // no_queue: no segments were in flight before this ACK — skip RTT/CC/loss work.
        // Mirrors Linux "if (prior_packets_out == 0) goto no_queue".
        if (priorPktsOut == 0) {
            tcpAckProbe(conn);                                  // ≈ tcp_ack_probe
            return 1;
        }

        // RTT sample — must happen BEFORE the RTX queue is drained so peekRtx() still
        // sees the just-ACKed segment (mirrors tcp_ack_update_rtt inside tcp_clean_rtx_queue).
        long rttUs = rttSample(conn, pkt);
        conn.rttEstimator().addSample(conn, rttUs);

        // Drain acknowledged entries from RTX queue + decrement packets_out
        // (≈ tcp_clean_rtx_queue drain loop + tp->packets_out -= acked_pcount).
        conn.cleanRtxQueue(ack);

        // RFC 5681 §3.2: loss detection — dupack / fast retransmit / RACK
        // (mirrors tcp_fastretrans_alert).
        conn.lossDetector().onAck(conn, ack, flags);

        // RFC 5681 §3.1: cwnd advancement — only when new data was acknowledged
        // (mirrors tcp_cong_avoid with newly_acked = prior_packets_out - tp->packets_out).
        if (0 != (flags & FLAG_SND_UNA_ADVANCED)) {
            int newlyAcked = Math.max(1, priorPktsOut - conn.packetsOut());
            conn.congestionControl().onAck(conn, newlyAcked, true);
            conn.rttEstimator().resetBackoff(conn);
        }

        return 1;
    }

    /**
     * Zero-window probe timer management — mirrors Linux {@code tcp_ack_probe}.
     * Called on the no_queue path (no in-flight segments before this ACK).
     * If the peer's window has opened and we have data queued, cancel the probe timer
     * so the send path can proceed normally.
     *
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_input.c#L3546">tcp_ack_probe</a>
     */
    private static void tcpAckProbe(TcpConnection conn) {
        if (conn.tcpSendHead() == null) {
            return;     // nothing to send — no probe timer active
        }
        if (conn.sndWnd() > 0) {
            // Window has opened — cancel probe timer; the send path will handle it.
            TcpRetransmitter.INSTANCE.cancelRetransmit(conn);
        }
    }

    /**
     * Guarded SND.WND update — mirrors Linux {@code tcp_ack_update_window}.
     *
     * @param newDataAcked {@code true} iff {@code SND.UNA} was advanced by this ACK
     * @return {@link #FLAG_WIN_UPDATE} if the window was updated, {@code 0} otherwise
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_input.c#L3688">tcp_ack_update_window</a>
     */
    private static int tcp_ack_update_window(TcpConnection conn, TcpPacketBuf pkt,
                                             boolean newDataAcked) {
        int seg  = pkt.tcpSeq();
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

    /**
     * Estimate RTT from the ACK, applying Karn's algorithm.
     *
     * @return RTT in microseconds, or {@code -1} to signal "skip" (retransmitted segment)
     */
    private static long rttSample(TcpConnection conn, TcpPacketBuf pkt) {
        TcpSegmentEntry head = conn.sendBuffer().peekRtx();
        if (head == null) return -1;
        if (head.isRetransmitted()) return -1;  // Karn: don't sample retransmits
        if (!after(pkt.tcpAckNum(), head.startSeq())) return -1;
        long nowUs = System.nanoTime() / 1_000L;
        return nowUs - head.sentTimeUs();
    }


}

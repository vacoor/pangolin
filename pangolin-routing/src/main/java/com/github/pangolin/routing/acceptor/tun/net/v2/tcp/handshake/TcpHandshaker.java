package com.github.pangolin.routing.acceptor.tun.net.v2.tcp.handshake;

import com.github.pangolin.routing.acceptor.tun.net.handler.support.TcpPacketBuf;
import com.github.pangolin.routing.acceptor.tun.net.handler.tcp.util.TcpOptionCodec;
import com.github.pangolin.routing.acceptor.tun.net.handler.tcp.util.TcpUtils;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.connection.TcpConnection;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.connection.TcpConnectionState;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.core.TcpPacketBuilder;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.internal.TcpConfig;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.internal.TcpConstants;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.pipeline.TcpConnectionChannel;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelPromise;
import io.netty.util.concurrent.ScheduledFuture;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.TimeUnit;

import static com.github.pangolin.routing.acceptor.tun.net.handler.support.TcpPacketBuf.*;

/**
 * Per-connection TCP 3-way handshake state machine.
 *
 * <p>Lifecycle:
 * <ol>
 *   <li>{@link #handshake(Channel, TcpPacketBuf)} — called on first SYN or retransmitted SYN.
 *       Sends SYN-ACK immediately (upstream connection is established later by
 *       {@code TcpEstablishedHandler}).</li>
 *   <li>{@link #finishHandshake(Channel, TcpPacketBuf)} — called when the final ACK arrives.
 *       Validates the ACK number and creates the {@link TcpConnection}.</li>
 * </ol>
 *
 * <p>All methods run on the connection's Worker EventLoop.
 *
 * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_input.c#tcp_conn_request">tcp_conn_request</a>
 */
public final class TcpHandshaker {

    private static final Logger log = LoggerFactory.getLogger(TcpHandshaker.class);

    /**
     * Maximum SYN-ACK retransmissions before giving up (≈ Linux tcp_synack_retries, default 5).
     */
    private static final int MAX_SYNACK_RETRIES = 5;
    /**
     * Initial SYN-ACK RTO: 1 s, doubled on each retransmit (RFC 6298 §5.5).
     */
    private static final long SYNACK_RTO_INIT_MS = 1_000L;

    // SYN-extracted client parameters
    private final int rcvIsn;        // client's initial sequence number (from SYN)
    private final int rcvNxt;        // rcvIsn + 1
    private final int clientMss;     // MSS advertised by client
    private final int clientWscale;  // window scale advertised by client (-1 if none)
    private final int clientInitWnd; // initial window from SYN segment
    private final byte[] srcAddrBytes;  // client IPv4/v6 address
    private final int srcPort;
    private final byte[] dstAddrBytes;  // our IPv4/v6 address
    private final int dstPort;

    // Our parameters
    private final int sndIsn;        // our initial sequence number
    private final TcpConfig config;

    // State
    private boolean synAckSent = false;
    private int synAckRetries = 0;          // retransmit counter
    /**
     * Epoch incremented on every {@link #sendSynAck} call.
     * Each write-callback captures a snapshot; only the latest snapshot arms the retransmit
     * timer.  Without this, a SYN retransmit that triggers a second {@code sendSynAck()} before
     * the first write-callback fires would cause both callbacks to call
     * {@code scheduleRetransmit()}, leaking a second timer.
     */
    private int synAckSendEpoch = 0;
    private ScheduledFuture<?> synAckTimer = null;       // retransmit timer handle

    TcpHandshaker(TcpPacketBuf synPkt, TcpConfig config) {
        this.config = config;
        this.srcAddrBytes = synPkt.srcAddrBytes();
        this.srcPort = synPkt.tcpSrcPort();
        this.dstAddrBytes = synPkt.dstAddrBytes();
        this.dstPort = synPkt.tcpDstPort();
        this.rcvIsn = synPkt.tcpSeq();
        this.rcvNxt = rcvIsn + 1;
        this.clientInitWnd = synPkt.tcpWindow();

        // Parse TCP options from SYN
        ByteBuf opts = synPkt.tcpOptionsSlice();
        int parsedMss = TcpOptionCodec.parseMss(opts);
        int parsedWscale = config.windowScalingEnabled() ? TcpOptionCodec.parseWindowScale(opts) : -1;
        this.clientMss = (parsedMss > 0) ? Math.min(parsedMss, config.mss()) : config.mss();
        this.clientWscale = parsedWscale;

        // Generate our ISN
        this.sndIsn = TcpUtils.secureSeq(srcAddrBytes, srcPort, dstAddrBytes, dstPort);
    }

    // ── Public API ─────────────────────────────────────────────────────────


    /**
     * Cancel the SYN-ACK retransmit timer.
     * Must be called on handshake completion ({@link #finishHandshake}) or channel close.
     * Safe to call multiple times.
     */
    void cancelRetransmitTimer() {
        if (synAckTimer != null) {
            synAckTimer.cancel(false);
            synAckTimer = null;
        }
    }

    /**
     * Process the initial SYN: sends SYN-ACK and arms the retransmit timer.
     * Called exactly once — SYN retransmits are handled by {@link #finishHandshake}.
     *
     * @param connChannel the {@code TcpConnectionChannel} for this connection
     * @param pkt         the SYN packet (not retained after return)
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_input.c#tcp_conn_request">tcp_conn_request</a>
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_ipv4.c#L2179">tcp_v4_rcv</a>
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_ipv4.c#tcp_v4_rcv">tcp_v4_rcv</a>
     */
    public void handshake(Channel connChannel, TcpPacketBuf pkt) {
        // 模拟连接上游代理.
        final ChannelPromise upstreamConnector = connChannel.newPromise();
        upstreamConnector.addListener((ChannelFutureListener) f -> {
            if (f.isSuccess()) {
                sendSynAck(connChannel);
            } else {
                // FIXME
                connChannel.close();
            }
        });
        connChannel.eventLoop().schedule(() -> {
            upstreamConnector.setSuccess();
        }, 1, TimeUnit.SECONDS);
    }

    /**
     * Process all packets after the initial SYN (≈ Linux {@code tcp_check_req}).
     *
     * <p>Handles, in order (matching {@code tcp_check_req} structure):
     * <ol>
     *   <li>SYN retransmit — pure SYN with {@code SEG.SEQ == rcvIsn}; checked <em>before</em>
     *       the window check, exactly as Linux does.</li>
     *   <li>Window check — segment must fall within {@code [rcvNxt, rcvNxt + rcvWnd)}.
     *       Out-of-window non-RST packets receive a challenge ACK; all out-of-window packets
     *       are then dropped.</li>
     *   <li>"Clash" — {@code SEG.SEQ == rcvIsn} but not a pure SYN (already handled above);
     *       drop silently.</li>
     *   <li>RST — window-validated; abort the half-open connection.</li>
     *   <li>SYN in window — new connection attempt collides; send RST and abort.</li>
     *   <li>Non-ACK — drop silently.</li>
     *   <li>Final ACK — validate ACK number; on success create and return {@link TcpConnection}.</li>
     * </ol>
     *
     * <p><b>Async SYN-ACK note:</b> unlike Linux (which sends SYN-ACK synchronously inside
     * {@code tcp_conn_request}), our {@link #handshake} defers SYN-ACK until the upstream
     * connection completes.  Consequently {@code synAckSent} may still be {@code false} during
     * that window.  For SYN retransmits we therefore guard on {@code synAckSent}: if the upstream
     * is still connecting we simply drop the retransmit — the SYN-ACK will be sent as soon as the
     * upstream is ready.  For the final ACK the guard is a safety net against programming errors
     * (both methods run on the same Worker EventLoop, so the timing is deterministic once the
     * upstream has connected).
     *
     * @return the created {@link TcpConnection} on handshake completion, {@code null} otherwise
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_minisocks.c#L660">tcp_check_req</a>
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_ipv4.c#L1742">tcp_v4_syn_recv_sock</a>
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_minisocks.c#L518">tcp_create_openreq_child</a>
     */
    public TcpConnection finishHandshake(Channel connChannel, TcpPacketBuf pkt) {
        int seq = pkt.tcpSeq();
        boolean flgSyn = pkt.isSyn();
        boolean flgRst = pkt.isRst();
        boolean flgAck = pkt.isAck();
        final int flg = pkt.tcpFlags() & (TCP_FLAG_RST | TCP_FLAG_SYN | TCP_FLAG_ACK);

        // Step 1: Pure SYN retransmit (tcp_check_req L~690).
        // Condition matches Linux: flg == TCP_FLAG_SYN (exactly — no RST, no ACK) and
        // seq == rcv_isn.  We skip the PAWS guard (no timestamp state on request socket).
        //
        // "Async SYN-ACK": unlike Linux, our handshake() defers SYN-ACK until the upstream
        // connection completes, so synAckSent may still be false here.  If it is, the upstream
        // is still connecting — drop the retransmit silently; sendSynAck() will fire on its own.
        if (seq == rcvIsn && TCP_FLAG_SYN == flg) {
            if (synAckSent) {
                synAckRetries = 0;   // reset backoff: client retransmit implies fresh start
                sendSynAck(connChannel);
            }
            return null;
        }

        // Step 2: ACK number check — BEFORE the window check (tcp_check_req L~730).
        // "RFC793 page 36: if the segment acknowledges something not yet sent, send RST."
        // (Linux: return sk → listening socket sends RST; we do it directly.)
        if (flgAck && pkt.tcpAckNum() != sndIsn + 1) {
            log.warn("[TCP] [HANDSHAKE] ACK number mismatch: expected {}, got {}",
                    Integer.toUnsignedString(sndIsn + 1),
                    Integer.toUnsignedString(pkt.tcpAckNum()));
            sendRst(connChannel, pkt).addListener((ChannelFutureListener) f -> connChannel.close());
            return null;
        }

        // Step 3: Window check — segment must fall within [rcvNxt, rcvNxt + rcvWnd).
        // Mirrors tcp_check_req: paws_reject || !tcp_in_window(...).
        // We skip PAWS (no timestamp state on the request socket).
        int endSeq = seq + pkt.tcpPayloadLength()
                + (flgSyn ? 1 : 0) + (pkt.isFin() ? 1 : 0);
        if (!tcpInWindow(seq, endSeq, rcvNxt, rcvNxt + config.initialRcvWnd())) {
            // Out of window: send challenge ACK (unless RST), then drop.
            if (!flgRst) {
                sendChallengeAck(connChannel);
            }
            return null;
        }

        // Step 4: Truncate SYN (tcp_check_req L~760).
        // seq == rcv_isn means the SYN byte is before the current window start (rcvNxt);
        // strip it so the RST/SYN check below treats it only by the remaining flags.
        if (seq == rcvIsn) {
            flgSyn = false;
        }

        // Step 5: RST or (remaining) SYN → embryonic_reset (tcp_check_req L~764).
        // - RST in window:    abort silently, no reply (non-fastopen path).
        // - SYN in window:    bad/colliding SYN → send RST and abort.
        // RST takes priority when both flags are set.
        if (flgRst || flgSyn) {
            if (flgRst) {
                log.debug("[TCP] [HANDSHAKE] RST received — aborting handshake");
                cancelRetransmitTimer();
                connChannel.close();
            } else {
                log.debug("[TCP] [HANDSHAKE] SYN in window — sending RST and aborting");
                cancelRetransmitTimer();
                sendRst(connChannel, pkt)
                        .addListener((ChannelFutureListener) f -> connChannel.close());
            }
            return null;
        }

        // Step 6: ACK must be set to complete the handshake (tcp_check_req L~778).
        if (!flgAck) {
            return null;
        }

        // Step 7: Guard — SYN-ACK must have been sent.
        // "Async SYN-ACK": synAckSent is set synchronously in sendSynAck() before writeAndFlush(),
        // and both methods run on the same Worker EventLoop, so once the upstream has connected
        // this flag is always true when a legitimate final ACK arrives.  Failing here indicates a
        // programming error (final ACK arrived before upstream connected), not a network race.
        if (!synAckSent) {
            log.warn("[TCP] [HANDSHAKE] ACK received before SYN-ACK was sent — dropping");
            return null;
        }

        // Step 8: Promote to ESTABLISHED (tcp_check_req L~793 → tcp_v4_syn_recv_sock).
        int negotiatedMss = Math.min(clientMss, config.mss());
        int peerWnd = pkt.tcpWindow();
        if (clientWscale >= 0) {
            peerWnd <<= clientWscale;
        }

        cancelRetransmitTimer();  // handshake done — stop retransmitting SYN-ACK
        log.debug("[TCP] [HANDSHAKE] 3WH complete: mss={}, peerWscale={}", negotiatedMss, clientWscale);

        TcpConnection conn = TcpConnection.builder()
                .channel(connChannel)
                .sndUna(sndIsn + 1)
                .sndNxt(sndIsn + 1)
                .rcvNxt(rcvNxt)
                .rcvWup(rcvNxt)
                .sndWnd(peerWnd)
                .rcvWnd(config.initialRcvWnd())
                .mss(negotiatedMss)
                .sndWscale(clientWscale >= 0 ? clientWscale : 0)
                .rcvWscale(clientWscale >= 0 ? config.windowScale() : 0)
                .build();
        conn.state(TcpConnectionState.TCP_SYN_RECV);
        return conn;
    }

    // ── Private helpers ────────────────────────────────────────────────────

    private void sendSynAck(Channel connChannel) {
        byte[] opts = buildSynAckOptions();
        int window = config.initialRcvWnd() >> (clientWscale >= 0 ? config.windowScale() : 0);
        if (window > TcpConstants.TCP_MAX_WINDOW) window = TcpConstants.TCP_MAX_WINDOW;

        // flags: SYN(0x02) + ACK(0x10) = 0x12
        ByteBuf buf = TcpPacketBuilder.buildRaw(
                dstAddrBytes, dstPort,
                srcAddrBytes, srcPort,
                sndIsn, rcvNxt,
                0x12,    // SYN+ACK
                window,
                opts, null, 0
        );

        // Cancel any running retransmit timer before (re-)sending.
        // A client SYN retransmit resets the backoff counter (synAckRetries already set to 0
        // by the caller); cancelling here ensures a clean timer state for the new attempt.
        cancelRetransmitTimer();

        // Mark synAckSent synchronously before writeAndFlush(): both sendSynAck() and
        // finishHandshake() run on the same Worker EventLoop, so by the time the final ACK
        // reaches finishHandshake() this flag is already true regardless of whether the
        // write has completed on the wire.
        synAckSent = true;

        // Capture send epoch: if a SYN retransmit triggers a second sendSynAck() before the
        // first write callback fires (both writes in flight simultaneously), only the callback
        // matching the latest epoch should arm the retransmit timer.  The stale callback
        // (epoch mismatch) skips scheduleRetransmit() and avoids leaking a second timer.
        final int epoch = ++synAckSendEpoch;
        connChannel.writeAndFlush(buf).addListener((ChannelFutureListener) f -> {
            if (!f.isSuccess()) {
                log.warn("[TCP] [HANDSHAKE] SYN-ACK send failed", f.cause());
                return;
            }
            if (epoch != synAckSendEpoch) {
                // A newer sendSynAck() superseded this one; let that callback arm the timer.
                return;
            }
            log.debug("[TCP] [HANDSHAKE] SYN-ACK sent (isn={}, retry={})",
                    Integer.toUnsignedString(sndIsn), synAckRetries);
            scheduleRetransmit(connChannel);   // arm retransmit timer (RFC 6298 §5.5)
        });
    }

    /**
     * Schedule the next SYN-ACK retransmission with exponential backoff.
     * Mirrors Linux {@code tcp_timeout_init()} / {@code inet_csk_reset_xmit_timer()}.
     * Gives up after {@link #MAX_SYNACK_RETRIES} and closes the channel.
     */
    private void scheduleRetransmit(Channel connChannel) {
        if (synAckRetries >= MAX_SYNACK_RETRIES) {
            log.debug("[TCP] [HANDSHAKE] SYN-ACK max retransmits ({}) reached — closing",
                    MAX_SYNACK_RETRIES);
            connChannel.close();
            return;
        }
        // Exponential backoff: 1 s, 2 s, 4 s, 8 s, 16 s (capped at 64 s)
        long delayMs = SYNACK_RTO_INIT_MS << Math.min(synAckRetries, 6);
        synAckTimer = connChannel.eventLoop().schedule(() -> {
            synAckRetries++;
            log.debug("[TCP] [HANDSHAKE] SYN-ACK retransmit #{}", synAckRetries);
            sendSynAck(connChannel);
        }, delayMs, TimeUnit.MILLISECONDS);
    }

    private byte[] buildSynAckOptions() {
        ByteBuf tmp = Unpooled.buffer(12);
        try {
            int ourMss = config.mss();
            int ourWscale = (clientWscale >= 0) ? config.windowScale() : -1;
            TcpOptionCodec.writeSynOptions(tmp, ourMss, ourWscale);
            byte[] result = new byte[tmp.readableBytes()];
            tmp.readBytes(result);
            return result;
        } finally {
            tmp.release();
        }
    }

    /**
     * TCP window membership check, mirroring Linux {@code tcp_in_window()}.
     *
     * <p>A zero-length segment (pure ACK) at the start of the window is always considered
     * in-window; this covers the common case of the final handshake ACK arriving at exactly
     * {@code rcvNxt}.
     *
     * @param seq    SEG.SEQ of the incoming segment
     * @param endSeq SEG.SEQ + payload_len + SYN/FIN occupancy
     * @param sWin   RCV.NXT (start of the receive window, inclusive)
     * @param eWin   RCV.NXT + RCV.WND (end of the receive window, exclusive)
     */
    private static boolean tcpInWindow(int seq, int endSeq, int sWin, int eWin) {
        // Pure ACK / zero-length segment at the start of the window (seq == sWin && len == 0).
        if (seq == sWin && seq == endSeq) {
            return true;
        }
        // after(endSeq, sWin) && before(seq, eWin) — all comparisons in circular 32-bit space.
        return (endSeq - sWin) > 0 && (seq - eWin) < 0;
    }

    /**
     * Send a challenge ACK for out-of-window segments (RFC 9293 §3.5 / Linux {@code tcp_check_req}).
     *
     * <p>The challenge ACK carries our current handshake state: {@code seq = sndIsn + 1},
     * {@code ack = rcvNxt}, and the same receive window we advertised in the SYN-ACK.
     * It does <em>not</em> affect the SYN-ACK retransmit timer.
     */
    private void sendChallengeAck(Channel connChannel) {
        int window = config.initialRcvWnd() >> (clientWscale >= 0 ? config.windowScale() : 0);
        if (window > TcpConstants.TCP_MAX_WINDOW) window = TcpConstants.TCP_MAX_WINDOW;
        ByteBuf buf = TcpPacketBuilder.buildRaw(
                dstAddrBytes, dstPort,
                srcAddrBytes, srcPort,
                sndIsn + 1, rcvNxt,
                0x10,   // ACK
                window,
                null, null, 0
        );
        ((TcpConnectionChannel) connChannel).writeRaw(buf);
    }

    private ChannelFuture sendRst(Channel connChannel, TcpPacketBuf pkt) {
        int seq = pkt.tcpAckNum();
        ByteBuf buf = TcpPacketBuilder.buildRaw(
                dstAddrBytes, dstPort,
                srcAddrBytes, srcPort,
                seq, 0,
                0x04,  // RST
                0, null, null, 0
        );
        // Use writeRaw() to bypass this channel's pipeline handlers (e.g., TcpEstablishedHandler)
        // which would intercept write() and treat ByteBuf as application data.
        return ((TcpConnectionChannel) connChannel).writeRaw(buf);
    }
}

package com.github.pangolin.routing.acceptor.tun.net.v2.tcp.handshake;

import com.github.pangolin.routing.acceptor.tun.net.handler.support.TcpPacketBuf;
import com.github.pangolin.routing.acceptor.tun.net.handler.tcp.util.TcpOptionCodec;
import com.github.pangolin.routing.acceptor.tun.net.handler.tcp.util.TcpUtils;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.connection.TcpConnection;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.core.TcpPacketBuilder;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.internal.TcpConfig;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.internal.TcpConstants;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.util.concurrent.ScheduledFuture;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.security.SecureRandom;
import java.util.concurrent.TimeUnit;

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
    private static final SecureRandom RANDOM = new SecureRandom();

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
     * RFC 9293 §3.5.2 — RST acceptability in SYN-RECEIVED state.
     * A RST is valid only when {@code SEG.SEQ == RCV.NXT}.
     * Call this before closing the channel on RST to avoid blind RST attacks.
     */
    boolean isRstAcceptable(TcpPacketBuf pkt) {
        return pkt.tcpSeq() == rcvNxt;
    }

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
     */
    public void handshake(Channel connChannel, TcpPacketBuf pkt) {
        sendSynAck(connChannel);
    }

    /**
     * Process all packets after the initial SYN (≈ Linux {@code tcp_check_req}).
     *
     * <p>Handles, in order:
     * <ol>
     *   <li>RST — validate sequence (RFC 9293 §3.5.2); if acceptable, cancel timer and close.</li>
     *   <li>SYN retransmit — client re-sent SYN; reset backoff and re-send SYN-ACK.</li>
     *   <li>Non-ACK — drop silently.</li>
     *   <li>Final ACK — validate ACK number; on success create and return {@link TcpConnection}.</li>
     * </ol>
     *
     * @return the created {@link TcpConnection} on handshake completion, {@code null} otherwise
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_ipv4.c#L2179">tcp_v4_rcv</a> TCP_NEW_SYN_RECV
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_minisocks.c#L660">tcp_check_req</a>
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_ipv4.c#L1742">tcp_v4_syn_recv_sock</a>
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_minisocks.c#L518">tcp_create_openreq_child</a> <==
     */
    public TcpConnection finishHandshake(Channel connChannel, TcpPacketBuf pkt) {
        // Step 1: RST — validate seq, the clean up half-open connection (RFC 9293 §3.5.2).
        if (pkt.isRst()) {
            if (isRstAcceptable(pkt)) {
                log.debug("[TCP] [HANDSHAKE] RST received — aborting handshake");
                cancelRetransmitTimer();
                connChannel.close();
            }
            // out-of-window RST: drop silently
            return null;
        }

        // Step 2: SYN retransmit (tcpSeq == rcvIsn) - only retransmit SYN-ACK  if it was already sent.
        if (pkt.isSyn()) {
            if (pkt.tcpSeq() == rcvIsn && synAckSent) {
                // TODO check it.
                synAckRetries = 0;   // reset backoff: client restart implies fresh start
                sendSynAck(connChannel);
            }
            return null;
        }

        // Step 3: Only ACK can complete the handshake
        if (!pkt.isAck()) {
            return null;
        }

        // Step 4: Guard — SYN-ACK must have been sent (async write may still be in-flight,
        // but synAckSent is set synchronously in sendSynAck() before writeAndFlush())
        if (!synAckSent) {
            log.warn("[TCP] [HANDSHAKE] ACK received before SYN-ACK was sent — dropping");
            return null;
        }

        // Step 5: ACK number must acknowledge exactly our SYN-ACK (snt_isn + 1)
        if (pkt.tcpAckNum() != sndIsn + 1) {
            log.warn("[TCP] [HANDSHAKE] ACK number mismatch: expected {}, got {}",
                    Integer.toUnsignedString(sndIsn + 1),
                    Integer.toUnsignedString(pkt.tcpAckNum()));
            sendRst(connChannel, pkt);
            return null;
        }

        // Step 6: All checks passed — create the child socket -> build the established connection.
        /*-
         * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_ipv4.c#L2179">tcp_v4_rcv</a> TCP_NEW_SYN_RECV
         * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_minisocks.c#L660">tcp_check_req</a>
         * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_ipv4.c#L1742">tcp_v4_syn_recv_sock</a>
         * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_minisocks.c#L518">tcp_create_openreq_child</a> <==
         */
        int negotiatedMss = Math.min(clientMss, config.mss());
        int peerWnd = pkt.tcpWindow();
        if (clientWscale >= 0) {
            peerWnd <<= clientWscale;
        }

        cancelRetransmitTimer();  // handshake done — stop retransmitting SYN-ACK
        log.debug("[TCP] [HANDSHAKE] 3WH complete: mss={}, peerWscale={}", negotiatedMss, clientWscale);

        return TcpConnection.builder()
                .channel(connChannel)
                .sndUna(sndIsn + 1)
                .sndNxt(sndIsn + 1)
                .rcvNxt(rcvNxt)
                .sndWnd(peerWnd)
                .rcvWnd(config.initialRcvWnd())
                .mss(negotiatedMss)
                .sndWscale(clientWscale >= 0 ? clientWscale : 0)
                .rcvWscale(clientWscale >= 0 ? config.windowScale() : 0)
                .build();
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

        // Mark synAckSent synchronously: both this method and finishHandshake() run on the same
        // Worker EventLoop, but writeAndFlush() is async (dispatched to TUN EventLoop). If the
        // final ACK arrives before the write-complete callback fires, the async flag would still
        // be false and finishHandshake() would incorrectly reject the handshake.
        synAckSent = true;
        connChannel.writeAndFlush(buf).addListener((ChannelFutureListener) f -> {
            if (f.isSuccess()) {
                log.debug("[TCP] [HANDSHAKE] SYN-ACK sent (isn={}, retry={})",
                        Integer.toUnsignedString(sndIsn), synAckRetries);
                scheduleRetransmit(connChannel);   // arm retransmit timer (RFC 6298 §5.5)
            } else {
                log.warn("[TCP] [HANDSHAKE] SYN-ACK send failed", f.cause());
            }
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

    private void sendRst(Channel connChannel, TcpPacketBuf pkt) {
        int seq = pkt.tcpAckNum();
        ByteBuf buf = TcpPacketBuilder.buildRaw(
                dstAddrBytes, dstPort,
                srcAddrBytes, srcPort,
                seq, 0,
                0x04,  // RST
                0, null, null, 0
        );
        connChannel.writeAndFlush(buf);
    }
}

package com.github.pangolin.routing.acceptor.tun.net.v2.tcp.handshake;

import com.github.pangolin.routing.acceptor.tun.net.handler.support.TcpPacketBuf;
import com.github.pangolin.routing.acceptor.tun.net.handler.tcp.util.TcpOptionCodec;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.connection.TcpConnection;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.core.TcpPacketBuilder;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.internal.TcpConfig;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.internal.TcpConstants;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.security.SecureRandom;

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
 */
public final class TcpHandshaker {

    private static final Logger log = LoggerFactory.getLogger(TcpHandshaker.class);
    private static final SecureRandom RANDOM = new SecureRandom();

    // SYN-extracted client parameters
    private final int    rcvIsn;        // client's initial sequence number (from SYN)
    private final int    rcvNxt;        // rcvIsn + 1
    private final int    clientMss;     // MSS advertised by client
    private final int    clientWscale;  // window scale advertised by client (-1 if none)
    private final int    clientInitWnd; // initial window from SYN segment
    private final byte[] srcAddrBytes;  // client IPv4/v6 address
    private final int    srcPort;
    private final byte[] dstAddrBytes;  // our IPv4/v6 address
    private final int    dstPort;

    // Our parameters
    private final int    sndIsn;        // our initial sequence number
    private final TcpConfig config;

    // State
    private boolean synAckSent = false;

    TcpHandshaker(TcpPacketBuf synPkt, TcpConfig config) {
        this.config      = config;
        this.srcAddrBytes = synPkt.srcAddrBytes();
        this.srcPort      = synPkt.tcpSrcPort();
        this.dstAddrBytes = synPkt.dstAddrBytes();
        this.dstPort      = synPkt.tcpDstPort();
        this.rcvIsn       = synPkt.tcpSeq();
        this.rcvNxt       = rcvIsn + 1;
        this.clientInitWnd = synPkt.tcpWindow();

        // Parse TCP options from SYN
        ByteBuf opts = synPkt.tcpOptionsSlice();
        int parsedMss    = TcpOptionCodec.parseMss(opts);
        int parsedWscale = config.windowScalingEnabled() ? TcpOptionCodec.parseWindowScale(opts) : -1;
        this.clientMss   = (parsedMss > 0) ? Math.min(parsedMss, config.mss()) : config.mss();
        this.clientWscale = parsedWscale;

        // Generate our ISN
        this.sndIsn = RANDOM.nextInt();
    }

    /**
     * Process a SYN (or retransmitted SYN). Idempotent — sends SYN-ACK on first call,
     * re-sends on retransmit.
     *
     * @param connChannel the {@code TcpConnectionChannel} for this connection
     * @param pkt         the SYN packet (must not be retained after return)
     */
    public void handshake(Channel connChannel, TcpPacketBuf pkt) {
        sendSynAck(connChannel);
    }

    /**
     * Process the final ACK completing the 3-way handshake.
     *
     * @return the created {@link TcpConnection}, or {@code null} if the ACK is invalid
     */
    public TcpConnection finishHandshake(Channel connChannel, TcpPacketBuf pkt) {
        if (!synAckSent) {
            log.warn("[TCP] [HANDSHAKE] ACK received before SYN-ACK was sent — dropping");
            return null;
        }
        // ACK must acknowledge exactly snd_isn + 1
        if (pkt.tcpAckNum() != sndIsn + 1) {
            log.warn("[TCP] [HANDSHAKE] ACK number mismatch: expected {}, got {}",
                    Integer.toUnsignedString(sndIsn + 1),
                    Integer.toUnsignedString(pkt.tcpAckNum()));
            sendRst(connChannel, pkt);
            return null;
        }

        int negotiatedMss = Math.min(clientMss, config.mss());
        int peerWnd = pkt.tcpWindow();
        if (clientWscale >= 0) {
            peerWnd <<= clientWscale;
        }

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
        int window  = config.initialRcvWnd() >> (clientWscale >= 0 ? config.windowScale() : 0);
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

        // Mark synAckSent synchronously: both this method and finishHandshake() run on the same
        // Worker EventLoop, but writeAndFlush() is async (dispatched to TUN EventLoop). If the
        // final ACK arrives before the write-complete callback fires, the async flag would still
        // be false and finishHandshake() would incorrectly reject the handshake.
        synAckSent = true;
        connChannel.writeAndFlush(buf).addListener((ChannelFutureListener) f -> {
            if (f.isSuccess()) {
                log.debug("[TCP] [HANDSHAKE] SYN-ACK sent (isn={})", Integer.toUnsignedString(sndIsn));
            } else {
                log.warn("[TCP] [HANDSHAKE] SYN-ACK send failed", f.cause());
            }
        });
    }

    private byte[] buildSynAckOptions() {
        ByteBuf tmp = Unpooled.buffer(12);
        try {
            int ourMss    = config.mss();
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
        int seq   = pkt.tcpAckNum();
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

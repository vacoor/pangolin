package com.github.pangolin.routing.acceptor.tun.net.v2.tcp.handshake;

import com.github.pangolin.routing.acceptor.tun.net.handler.support.TcpPacketBuf;
import com.github.pangolin.routing.acceptor.tun.net.handler.tcp.util.TcpOptionCodec;
import com.github.pangolin.routing.acceptor.tun.net.handler.tcp.util.TcpUtils;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.core.TcpOutput;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.internal.FourTuple;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.internal.TcpConfig;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.internal.TcpConstants;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.ng.TcpMultiplexer.TcpSock;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.util.concurrent.ScheduledFuture;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.TimeUnit;

import static com.github.pangolin.routing.acceptor.tun.net.handler.support.TcpPacketBuf.TCP_FLAG_ACK;
import static com.github.pangolin.routing.acceptor.tun.net.handler.support.TcpPacketBuf.TCP_FLAG_RST;
import static com.github.pangolin.routing.acceptor.tun.net.handler.support.TcpPacketBuf.TCP_FLAG_SYN;

public final class TcpHandshaker {

    private static final Logger log = LoggerFactory.getLogger(TcpHandshaker.class);
    private static final int MAX_SYNACK_RETRIES = 5;
    private static final long SYNACK_RTO_INIT_MS = 1_000L;

    private final int rcvIsn;
    private final int rcvNxt;
    private final int clientMss;
    private final int clientWscale;
    private final boolean clientTimestamp;
    private final int clientTsVal;
    private final int clientInitWnd;
    private final byte[] srcAddrBytes;
    private final int srcPort;
    private final byte[] dstAddrBytes;
    private final int dstPort;
    private final int sndIsn;
    private final TcpConfig config;

    private boolean synAckSent = false;
    private int synAckRetries = 0;
    private int synAckSendEpoch = 0;
    private ScheduledFuture<?> synAckTimer = null;
    private Runnable synAckFailureAction = () -> {};

    TcpHandshaker(TcpPacketBuf synPkt, TcpConfig config) {
        this.config = config;
        this.srcAddrBytes = synPkt.srcAddrBytes();
        this.srcPort = synPkt.tcpSrcPort();
        this.dstAddrBytes = synPkt.dstAddrBytes();
        this.dstPort = synPkt.tcpDstPort();
        this.rcvIsn = synPkt.tcpSeq();
        this.rcvNxt = rcvIsn + 1;
        this.clientInitWnd = synPkt.tcpWindow();

        ByteBuf opts = synPkt.tcpOptionsSlice();
        int parsedMss = TcpOptionCodec.parseMss(opts);
        int parsedWscale = config.windowScalingEnabled() ? TcpOptionCodec.parseWindowScale(opts) : -1;
        long[] parsedTs = config.timestampsEnabled() ? TcpOptionCodec.parseTimestamp(opts) : null;
        this.clientMss = (parsedMss > 0) ? Math.min(parsedMss, config.mss()) : config.mss();
        this.clientWscale = parsedWscale;
        this.clientTimestamp = parsedTs != null;
        this.clientTsVal = parsedTs != null ? (int) parsedTs[0] : 0;
        this.sndIsn = TcpUtils.secureSeq(srcAddrBytes, srcPort, dstAddrBytes, dstPort);
    }

    public int rcvIsn() {
        return rcvIsn;
    }

    public int rcvNxt() {
        return rcvNxt;
    }

    public int sndIsn() {
        return sndIsn;
    }

    public boolean synAckSent() {
        return synAckSent;
    }

    public void cancelRetransmitTimer() {
        if (synAckTimer != null) {
            synAckTimer.cancel(false);
            synAckTimer = null;
        }
    }

    public void synAckFailureAction(Runnable action) {
        this.synAckFailureAction = action == null ? () -> {} : action;
    }

    public void retransmitSynAck(Channel connChannel) {
        if (!synAckSent) {
            return;
        }
        synAckRetries++;
        sendSynAck(connChannel);
    }

    public void sendSynAckAfterBackendConnected(Channel connChannel) {
        sendSynAck(connChannel);
    }

    public void abort() {
        cancelRetransmitTimer();
    }

    public ChannelFuture sendResetAndAbort(Channel connChannel, TcpPacketBuf pkt) {
        cancelRetransmitTimer();
        return sendRst(connChannel, pkt);
    }

    public TcpSock buildChildSock(Channel netChannel, Channel childChannel, TcpPacketBuf pkt) {
        int negotiatedMss = Math.min(clientMss, config.mss());
        int peerWnd = pkt.tcpWindow();
        if (clientWscale >= 0) {
            peerWnd <<= clientWscale;
        }

        cancelRetransmitTimer();
        log.debug("[TCP] [HANDSHAKE] 3WH complete: mss={}, peerWscale={}", negotiatedMss, clientWscale);

        return TcpSock.createChild(
                netChannel,
                childChannel,
                FourTuple.of(dstAddrBytes, dstPort, srcAddrBytes, srcPort),
                sndIsn,
                sndIsn + 1,
                rcvNxt,
                peerWnd,
                config.initialRcvWnd(),
                negotiatedMss,
                clientWscale >= 0 ? clientWscale : 0,
                clientWscale >= 0 ? config.windowScale() : 0,
                clientTimestamp,
                clientTsVal
        );
    }

    public TcpSock finishHandshake(Channel netChannel, Channel childChannel, TcpPacketBuf pkt) {
        final int seq = pkt.tcpSeq();
        if (pkt.isRst()) {
            if (seq == rcvNxt) {
                abort();
            }
            return null;
        }

        if (seq == rcvIsn && (pkt.tcpFlags() & (TCP_FLAG_RST | TCP_FLAG_SYN | TCP_FLAG_ACK)) == TCP_FLAG_SYN) {
            retransmitSynAck(netChannel);
            return null;
        }

        if (!pkt.isAck()) {
            return null;
        }

        if (pkt.tcpAckNum() != sndIsn + 1) {
            log.warn("[TCP] [HANDSHAKE] ACK number mismatch: expected {}, got {}",
                    Integer.toUnsignedString(sndIsn + 1),
                    Integer.toUnsignedString(pkt.tcpAckNum()));
            sendResetAndAbort(netChannel, pkt);
            return null;
        }

        return buildChildSock(netChannel, childChannel, pkt);
    }

    private void sendSynAck(Channel connChannel) {
        byte[] opts = buildSynAckOptions();
        int window = config.initialRcvWnd() >> (clientWscale >= 0 ? config.windowScale() : 0);
        if (window > TcpConstants.TCP_MAX_WINDOW) window = TcpConstants.TCP_MAX_WINDOW;

        cancelRetransmitTimer();
        synAckSent = true;
        final int epoch = ++synAckSendEpoch;
        TcpOutput.INSTANCE.tcp_send_synack(
                connChannel,
                dstAddrBytes, dstPort, srcAddrBytes, srcPort,
                sndIsn, rcvNxt, window, opts
        ).addListener((ChannelFutureListener) f -> {
            if (!f.isSuccess()) {
                log.warn("[TCP] [HANDSHAKE] SYN-ACK send failed", f.cause());
                return;
            }
            if (epoch != synAckSendEpoch) {
                return;
            }
            log.debug("[TCP] [HANDSHAKE] SYN-ACK sent (isn={}, retry={})",
                    Integer.toUnsignedString(sndIsn), synAckRetries);
            scheduleRetransmit(connChannel);
        });
    }

    private void scheduleRetransmit(Channel connChannel) {
        if (synAckRetries >= MAX_SYNACK_RETRIES) {
            log.debug("[TCP] [HANDSHAKE] SYN-ACK max retransmits ({}) reached - aborting half-open socket", MAX_SYNACK_RETRIES);
            synAckFailureAction.run();
            return;
        }
        long delayMs = SYNACK_RTO_INIT_MS << Math.min(synAckRetries, 6);
        synAckTimer = connChannel.eventLoop().schedule(() -> {
            synAckRetries++;
            log.debug("[TCP] [HANDSHAKE] SYN-ACK retransmit #{}", synAckRetries);
            sendSynAck(connChannel);
        }, delayMs, TimeUnit.MILLISECONDS);
    }

    private byte[] buildSynAckOptions() {
        ByteBuf tmp = Unpooled.buffer(clientTimestamp ? 24 : 12);
        try {
            int ourMss = config.mss();
            int ourWscale = (clientWscale >= 0) ? config.windowScale() : -1;
            Long tsval = clientTimestamp ? (System.nanoTime() / 1_000_000L) & 0xFFFFFFFFL : null;
            Long tsecr = clientTimestamp ? ((long) clientTsVal & 0xFFFFFFFFL) : null;
            TcpOptionCodec.writeSynOptions(tmp, ourMss, ourWscale, tsval, tsecr);
            byte[] result = new byte[tmp.readableBytes()];
            tmp.readBytes(result);
            return result;
        } finally {
            tmp.release();
        }
    }

    private void sendChallengeAck(Channel connChannel) {
        int window = config.initialRcvWnd() >> (clientWscale >= 0 ? config.windowScale() : 0);
        if (window > TcpConstants.TCP_MAX_WINDOW) window = TcpConstants.TCP_MAX_WINDOW;
        TcpOutput.INSTANCE.tcp_send_challenge_ack_handshake(
                connChannel,
                dstAddrBytes, dstPort, srcAddrBytes, srcPort,
                sndIsn + 1, rcvNxt, window);
    }

    private ChannelFuture sendRst(Channel connChannel, TcpPacketBuf pkt) {
        return TcpOutput.INSTANCE.tcp_send_reset_handshake(
                connChannel,
                dstAddrBytes, dstPort, srcAddrBytes, srcPort,
                pkt.tcpAckNum());
    }
}

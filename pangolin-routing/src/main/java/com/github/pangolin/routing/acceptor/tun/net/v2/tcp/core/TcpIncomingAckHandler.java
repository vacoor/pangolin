package com.github.pangolin.routing.acceptor.tun.net.v2.tcp.core;

import com.github.pangolin.routing.acceptor.tun.net.handler.support.TcpPacketBuf;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.connection.ConnectionKey;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.connection.TcpSendBuffer.TcpSegmentEntry;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.ng.TcpIncomingPreValidator;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.ng.TcpMultiplexer.TcpSock;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import org.slf4j.Logger;

import static com.github.pangolin.routing.acceptor.tun.net.handler.tcp.util.TcpUtils.determineEndSeq;
import static com.github.pangolin.routing.acceptor.tun.net.v2.tcp.core.SkbDropReasonConstants.SKB_DROP_REASON_TCP_ACK_UNSENT_DATA;
import static com.github.pangolin.routing.acceptor.tun.net.v2.tcp.core.SkbDropReasonConstants.SKB_DROP_REASON_TCP_TOO_OLD_ACK;
import static com.github.pangolin.routing.acceptor.tun.net.v2.tcp.internal.TcpSequence.after;
import static com.github.pangolin.routing.acceptor.tun.net.v2.tcp.internal.TcpSequence.before;

public final class TcpIncomingAckHandler extends ChannelInboundHandlerAdapter {

    public enum AckResult {
        NONE,
        OLD_OR_DUP,
        NEW_DATA_ACKED
    }

    public static final ConnectionKey<AckResult> ACK_RESULT_KEY =
            ConnectionKey.of("tcp.segment-validator.ack-result");

    public static final int FLAG_DATA = 0x01;
    public static final int FLAG_WIN_UPDATE = 0x02;
    public static final int FLAG_SLOWPATH = 0x100;
    public static final int FLAG_SND_UNA_ADVANCED = 0x400;
    public static final int FLAG_UPDATE_TS_RECENT = 0x4000;
    public static final int FLAG_NO_CHALLENGE_ACK = 0x8000;

    private final TcpSock sock;
    private final Logger log;
    private final TcpIncomingPreValidator incomingValidator;
    private ChannelPromise closePromise;

    public TcpIncomingAckHandler(TcpSock sock, Logger log) {
        this.sock = sock;
        this.log = log;
        this.incomingValidator = new TcpIncomingPreValidator(sock);
    }

    public void closePromise(ChannelPromise p) {
        this.closePromise = p;
        this.incomingValidator.closePromise(p);
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        if (!(msg instanceof TcpPacketBuf)) {
            ctx.fireChannelRead(msg);
            return;
        }
        TcpPacketBuf pkt = (TcpPacketBuf) msg;
        if (!incomingValidator.validate(ctx, pkt)) {
            return;
        }

        final int priorSndUna = sock.sndUna();
        int reason = tcpAck(sock, pkt, FLAG_SLOWPATH | FLAG_UPDATE_TS_RECENT | FLAG_NO_CHALLENGE_ACK);
        if (reason <= 0) {
            if (sock.state() == com.github.pangolin.routing.acceptor.tun.net.v2.tcp.connection.TcpConnectionState.TCP_SYN_RECV) {
                return;
            }
            if (reason < 0) {
                TcpOutput.INSTANCE.tcp_send_challenge_ack(sock, false);
                return;
            }
        }

        if (after(sock.sndUna(), priorSndUna)) {
            TcpRetransmitter.INSTANCE.rearmRto(sock);
        }
        ctx.fireChannelRead(msg);
    }

    public static int tcpAck(TcpSock sock, TcpPacketBuf pkt, int flags) {
        final int priorSndUna = sock.sndUna();
        final int priorPktsOut = sock.packetsOut();
        final int ackSeq = pkt.tcpSeq();
        final int ack = pkt.tcpAckNum();

        if (before(ack, priorSndUna)) {
            final int maxWindow = (int) Math.min(sock.maxWindow(), sock.bytesAcked());
            if (before(ack, priorSndUna - maxWindow)) {
                if ((flags & FLAG_NO_CHALLENGE_ACK) == 0) {
                    TcpOutput.INSTANCE.tcp_send_challenge_ack(sock, false);
                }
                return -SKB_DROP_REASON_TCP_TOO_OLD_ACK;
            }
            return 0;
        }

        if (after(ack, sock.sndNxt())) {
            return -SKB_DROP_REASON_TCP_ACK_UNSENT_DATA;
        }

        if (after(ack, priorSndUna)) {
            flags |= FLAG_SND_UNA_ADVANCED;
        }

        if ((flags & FLAG_UPDATE_TS_RECENT) != 0) {
            sock.updateRecentTimestamp(ackSeq);
        }

        if ((flags & (FLAG_SLOWPATH | FLAG_SND_UNA_ADVANCED)) == FLAG_SND_UNA_ADVANCED) {
            sock.sndWl1(ackSeq);
            flags |= FLAG_WIN_UPDATE;
        } else {
            if (ackSeq != determineEndSeq(pkt)) {
                flags |= FLAG_DATA;
            }
            flags |= tcpAckUpdateWindow(sock, pkt, (flags & FLAG_SND_UNA_ADVANCED) != 0);
        }

        sock.sndUnaUpdate(ack);

        if (priorPktsOut == 0) {
            tcpAckProbe(sock);
            return 1;
        }

        long rttUs = rttSample(sock, pkt);
        sock.addRttSample(rttUs);
        sock.cleanRtxQueue(ack);

        if ((flags & FLAG_SND_UNA_ADVANCED) != 0) {
            int newlyAcked = Math.max(1, priorPktsOut - sock.packetsOut());
            sock.onAckedByCc(newlyAcked, true);
            sock.resetRtoBackoff();
        }
        return 1;
    }

    public static void tcpAckProbe(TcpSock sock) {
        if (sock.tcpSendHead() == null) {
            return;
        }
        if (sock.sndWnd() > 0) {
            TcpRetransmitter.INSTANCE.cancelRetransmit(sock);
        }
    }

    public static int tcpAckUpdateWindow(TcpSock sock, TcpPacketBuf pkt, boolean newDataAcked) {
        int seg = pkt.tcpSeq();
        int nwin = pkt.tcpWindow() << sock.sndWscale();
        if (newDataAcked
                || after(seg, sock.sndWl1())
                || (seg == sock.sndWl1() && (before(sock.sndWnd(), nwin) || nwin == 0))) {
            sock.sndWl1(seg);
            sock.sndWnd(nwin);
            return FLAG_WIN_UPDATE;
        }
        return 0;
    }

    public static long rttSample(TcpSock sock, TcpPacketBuf pkt) {
        TcpSegmentEntry head = sock.sendBuffer().peekRtx();
        if (head == null || head.isRetransmitted() || !after(pkt.tcpAckNum(), head.startSeq())) {
            return -1;
        }
        long nowUs = System.nanoTime() / 1_000L;
        return nowUs - head.sentTimeUs();
    }
}

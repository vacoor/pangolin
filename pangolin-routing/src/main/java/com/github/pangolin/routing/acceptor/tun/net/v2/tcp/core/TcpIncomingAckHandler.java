package com.github.pangolin.routing.acceptor.tun.net.v2.tcp.core;

import com.github.pangolin.routing.acceptor.tun.net.handler.support.TcpPacketBuf;
import com.github.pangolin.routing.acceptor.tun.net.handler.tcp.util.TcpOptionCodec;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.connection.ConnectionKey;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.connection.TcpSendBuffer.TcpSegmentEntry;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.ng.TcpIncomingPreValidator;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.ng.TcpMultiplexer.TcpSock;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.timer.TcpTimerScheduler;
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
        ctx.fireChannelRead(msg);
    }

    public static int tcpAck(TcpSock sock, TcpPacketBuf pkt, int flags) {
        final int priorSndUna = sock.sndUna();
        final int priorPktsOut = sock.packetsOut();
        final int ackSeq = pkt.tcpSeq();
        final int ack = pkt.tcpAckNum();
        sock.onSegmentReceived();

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
            int tsval = parseTimestampValue(pkt);
            if (tsval >= 0) {
                sock.updateRecentTimestamp(tsval);
            }
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
        sock.probesOut(0);

        if (priorPktsOut == 0) {
            tcpAckProbe(sock);
            return 1;
        }

        long rttUs = rttSample(sock, pkt);
        sock.addRttSample(rttUs);
        sock.cleanRtxQueue(ack);
        tcpProcessTlpAck(sock, ack);

        if (after(sock.sndUna(), priorSndUna)) {
            TcpRetransmitter.INSTANCE.rearmRto(sock);
            int newlyAcked = Math.max(1, priorPktsOut - sock.packetsOut());
            sock.onAckedByCc(newlyAcked, true);
            sock.resetRtoBackoff();
        } else if (priorPktsOut > 0
                && ack == priorSndUna
                && (flags & (FLAG_DATA | FLAG_WIN_UPDATE)) == 0) {
            sock.onAckedByCc(1, false);
        }

        return Math.max(flags, 1);
    }

    private static void tcpProcessTlpAck(TcpSock sock, int ack) {
        int tlpHighSeq = sock.tlpHighSeq();
        if (tlpHighSeq == 0) {
            return;
        }
        if (sock.packetsOut() == 0 || !before(ack, tlpHighSeq)) {
            sock.tlpHighSeq(0);
        }
    }

    public static void tcpAckProbe(TcpSock sock) {
        TcpSegmentEntry head = sock.tcpSendHead();
        if (head == null) {
            return;
        }
        int wndEnd = sock.sndUna() + sock.sndWnd();
        if (!after(head.endSeq(), wndEnd)) {
            sock.probeBackoffShift(0);
            sock.probesTstampMs(0L);
            TcpTimerScheduler.INSTANCE.cancelWriteTimer(sock);
            return;
        }
        long when = sock.tcpProbe0WhenMs(sock.tcpRtoMaxMs());
        when = sock.tcpClampProbe0ToUserTimeout(when);
        TcpTimerScheduler.INSTANCE.scheduleWriteTimer(
                sock,
                com.github.pangolin.routing.acceptor.tun.net.v2.tcp.timer.TimerType.ZERO_WINDOW_PROBE,
                when,
                () -> sock.probeTimerAction().run()
        );
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

    private static int parseTimestampValue(TcpPacketBuf pkt) {
        long[] ts = TcpOptionCodec.parseTimestamp(pkt.tcpOptionsSlice());
        return ts == null ? -1 : (int) ts[0];
    }
}

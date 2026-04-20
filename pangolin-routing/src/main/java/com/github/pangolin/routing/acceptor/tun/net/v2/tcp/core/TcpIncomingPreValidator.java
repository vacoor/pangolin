package com.github.pangolin.routing.acceptor.tun.net.v2.tcp.core;

import com.github.pangolin.routing.acceptor.tun.net.handler.support.TcpPacketBuf;
import com.github.pangolin.routing.acceptor.tun.net.handler.tcp.util.TcpOptionCodec;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.core.TcpConnectionState;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.core.TcpMib;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.core.TcpMibStats;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.core.TcpOutput;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.core.TcpConstants;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketException;

import static com.github.pangolin.routing.acceptor.tun.net.handler.tcp.util.TcpLogUtils.logFormat;
import static com.github.pangolin.routing.acceptor.tun.net.handler.tcp.util.TcpUtils.determineEndSeq;
import static com.github.pangolin.routing.acceptor.tun.net.v2.tcp.core.SkbDropReason.SKB_DROP_REASON_TCP_INVALID_SEQUENCE;
import static com.github.pangolin.routing.acceptor.tun.net.v2.tcp.core.SkbDropReason.SKB_DROP_REASON_TCP_INVALID_SYN;
import static com.github.pangolin.routing.acceptor.tun.net.v2.tcp.core.SkbDropReason.SKB_DROP_REASON_TCP_OLD_SEQUENCE;
import static com.github.pangolin.routing.acceptor.tun.net.v2.tcp.core.SkbDropReason.SKB_DROP_REASON_TCP_RESET;
import static com.github.pangolin.routing.acceptor.tun.net.v2.tcp.core.SkbDropReason.SKB_NOT_DROPPED_YET;
import static com.github.pangolin.routing.acceptor.tun.net.v2.tcp.core.TcpOutOps.tcp_oow_rate_limited;
import static com.github.pangolin.routing.acceptor.tun.net.v2.tcp.core.TcpSequence.after;
import static com.github.pangolin.routing.acceptor.tun.net.v2.tcp.core.TcpSequence.before;

public class TcpIncomingPreValidator {
    private static final Logger log = LoggerFactory.getLogger(TcpIncomingPreValidator.class);
    private final TcpSock sock;
    private final Runnable resetAction;
    private ChannelPromise closePromise;

    public TcpIncomingPreValidator(TcpSock sock) {
        this(sock, () -> {});
    }

    public TcpIncomingPreValidator(TcpSock sock, Runnable resetAction) {
        this.sock = sock;
        this.resetAction = resetAction == null ? () -> {} : resetAction;
    }

    public void closePromise(ChannelPromise p) {
        this.closePromise = p;
    }

    public boolean validate(ChannelHandlerContext ctx, TcpPacketBuf pkt) {
        if (!pkt.isAck() && !pkt.isRst() && !pkt.isSyn()) {
            log.warn(logFormat("[TCP] [RCV]", pkt, "Invalid TCP flag(!ACK, !RST, !SYN)"));
            pkt.release();
            return false;
        }

        if (!tcp_validate_incoming(ctx, pkt)) {
            pkt.release();
            return false;
        }
        return true;
    }

    private boolean tcp_validate_incoming(ChannelHandlerContext ctx, TcpPacketBuf pkt) {
        boolean accecnReflector = false;
        if (sock.timestampEnabled()) {
            ByteBuf opts = pkt.tcpOptionsSlice();
            long[] ts = TcpOptionCodec.parseTimestamp(opts);
            if (ts != null && sock.pawsRejected((int) ts[0])) {
                if (!pkt.isRst()) {
                    TcpMibStats.INSTANCE.inc(TcpMib.PAWSESTABREJECTED);
                    TcpOutput.INSTANCE.tcp_send_ack(sock);
                    return false;
                }
            }
        }

        int reason = tcp_sequence(sock, pkt);
        if (reason != SKB_NOT_DROPPED_YET) {
            TcpMibStats.INSTANCE.incDrop(reason);
            if (!pkt.isRst()) {
                sock.enterQuickAckMode(TcpConstants.TCP_MAX_QUICKACKS);
                sock.addAckPending(TcpConstants.ACK_SCHED);
                if (pkt.isSyn()) {
                    TcpMibStats.INSTANCE.inc(TcpMib.TCPSYNCHALLENGE);
                    TcpOutput.INSTANCE.tcp_send_challenge_ack(sock, accecnReflector);
                    return false;
                }
                if (!tcp_oow_rate_limited(sock, pkt)) {
                    TcpOutput.INSTANCE.tcp_send_ack(sock);
                }
            } else if (tcp_reset_check(sock, pkt)) {
                tcp_reset(ctx);
            }
            return false;
        }

        if (pkt.isRst()) {
            if (pkt.tcpSeq() == sock.rcvNxt() || tcp_reset_check(sock, pkt)) {
                tcp_reset(ctx);
                return false;
            }
            TcpOutput.INSTANCE.tcp_send_challenge_ack(sock, false);
            return false;
        }

        if (pkt.isSyn()) {
            int seq = pkt.tcpSeq();
            int endSeq = determineEndSeq(pkt);
            if (sock.state() == TcpConnectionState.TCP_SYN_RECV
                    && pkt.isAck()
                    && seq + 1 == endSeq
                    && seq + 1 == sock.rcvNxt()
                    && pkt.tcpAckNum() == sock.sndNxt()) {
                return true;
            }

            sock.enterQuickAckMode(TcpConstants.TCP_MAX_QUICKACKS);
            sock.addAckPending(TcpConstants.ACK_SCHED);
            TcpMibStats.INSTANCE.inc(TcpMib.TCPSYNCHALLENGE);
            TcpMibStats.INSTANCE.incDrop(SKB_DROP_REASON_TCP_INVALID_SYN);
            TcpOutput.INSTANCE.tcp_send_challenge_ack(sock, accecnReflector);
            return false;
        }

        return true;
    }

    private void tcp_reset(ChannelHandlerContext ctx) {
        final int err;
        switch (sock.state()) {
            case TCP_SYN_SENT:
                err = 111;
                break;
            case CLOSE_WAIT:
                err = 32;
                break;
            case TCP_CLOSED:
                return;
            default:
                err = 104;
                break;
        }
        sock.skErr(err);
        resetAction.run();
        if (closePromise != null && !closePromise.isDone()) {
            closePromise.trySuccess();
        }
        ctx.fireExceptionCaught(errException(err));
    }

    private static Exception errException(int err) {
        switch (err) {
            case 111:
                return new ConnectException("Connection refused");
            case 32:
                return new IOException("Broken pipe");
            default:
                return new SocketException("Connection reset");
        }
    }

    private static int tcp_sequence(TcpSock sock, TcpPacketBuf pkt) {
        int seq = pkt.tcpSeq();
        int segLen = pkt.tcpPayloadLength() + (pkt.isSyn() ? 1 : 0) + (pkt.isFin() ? 1 : 0);
        int endSeq = seq + segLen;
        int rcvWup = sock.rcvWup();
        int rcvNxt = sock.rcvNxt();
        int rcvWndEnd = rcvNxt + sock.tcp_receive_window();

        if (before(endSeq, rcvWup)) {
            return SKB_DROP_REASON_TCP_OLD_SEQUENCE;
        }
        if (after(endSeq, rcvWndEnd)) {
            if (!after(endSeq - (pkt.isFin() ? 1 : 0), rcvWndEnd)) {
                return SKB_NOT_DROPPED_YET;
            }
            if (after(seq, rcvWndEnd)) {
                return SKB_DROP_REASON_TCP_INVALID_SEQUENCE;
            }
        }
        return SKB_NOT_DROPPED_YET;
    }

    private static boolean tcp_reset_check(TcpSock sock, TcpPacketBuf pkt) {
        if (pkt.tcpSeq() != sock.rcvNxt() - 1) {
            return false;
        }
        TcpConnectionState s = sock.state();
        return s == TcpConnectionState.CLOSE_WAIT
                || s == TcpConnectionState.LAST_ACK
                || s == TcpConnectionState.CLOSING;
    }
}

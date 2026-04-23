package com.github.pangolin.routing.acceptor.tun.net.v2.tcp.core;

import com.github.pangolin.routing.acceptor.tun.net.codec.TcpPacketBuf;
import com.github.pangolin.routing.acceptor.tun.net.handler.tcp.util.TcpOptionCodec;

import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.core.hook.TcpSockHandler;
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
import static com.github.pangolin.routing.acceptor.tun.net.v2.tcp.core.SkbDropReason.SKB_NOT_DROPPED_YET;
import static com.github.pangolin.routing.acceptor.tun.net.v2.tcp.core.TcpOutOps.oowRateLimited;
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

        if (!validateIncoming(ctx, pkt)) {
            pkt.release();
            return false;
        }
        return true;
    }

    private boolean validateIncoming(ChannelHandlerContext ctx, TcpPacketBuf pkt) {
        boolean accecnReflector = false;
        if (sock.timestampEnabled()) {
            ByteBuf opts = pkt.tcpOptionsSlice();
            long[] ts = TcpOptionCodec.parseTimestamp(opts);
            if (ts != null && sock.pawsRejected((int) ts[0])) {
                if (!pkt.isRst()) {
                    sock.multiplexer().mib().inc(TcpMib.PAWSESTABREJECTED);
                    sock.sender().sendAck();
                    return false;
                }
            }
        }

        int reason = sequenceCheck(sock, pkt);
        if (reason != SKB_NOT_DROPPED_YET) {
            sock.multiplexer().mib().incDrop(reason);
            if (!pkt.isRst()) {
                sock.receiver().enterQuickAck(TcpConstants.TCP_MAX_QUICKACKS);
                sock.receiver().addAckPending(TcpConstants.ACK_SCHED);
                if (pkt.isSyn()) {
                    sock.multiplexer().mib().inc(TcpMib.TCPSYNCHALLENGE);
                    sock.sender().sendChallengeAck(accecnReflector);
                    return false;
                }
                if (!oowRateLimited(sock, pkt)) {
                    sock.sender().sendAck();
                }
            } else if (resetCheck(sock, pkt)) {
                resetIncoming(ctx);
            }
            return false;
        }

        if (pkt.isRst()) {
            if (pkt.tcpSeq() == sock.receiver().rcvNxt() || resetCheck(sock, pkt)) {
                resetIncoming(ctx);
                return false;
            }
            sock.sender().sendChallengeAck(false);
            return false;
        }

        if (pkt.isSyn()) {
            int seq = pkt.tcpSeq();
            int endSeq = determineEndSeq(pkt);
            if (sock.state() == TcpConnectionState.TCP_SYN_RECV
                    && pkt.isAck()
                    && seq + 1 == endSeq
                    && seq + 1 == sock.receiver().rcvNxt()
                    && pkt.tcpAckNum() == sock.sndNxt()) {
                return true;
            }

            sock.receiver().enterQuickAck(TcpConstants.TCP_MAX_QUICKACKS);
            sock.receiver().addAckPending(TcpConstants.ACK_SCHED);
            sock.multiplexer().mib().inc(TcpMib.TCPSYNCHALLENGE);
            sock.multiplexer().mib().incDrop(SKB_DROP_REASON_TCP_INVALID_SYN);
            sock.sender().sendChallengeAck(accecnReflector);
            return false;
        }

        return true;
    }

    private void resetIncoming(ChannelHandlerContext ctx) {
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

        /*
         * 在销毁 sock 之前先通知 handler —— 保证用户 pipeline 的
         * exceptionCaught 能拿到 cause,避免 resetAction → tcpDone 导致
         * channel 先 inactive 而丢失错误信息。handler 实现会 closeForcibly
         * 并清掉 sock.handler,后续 sock.close 不再二次回调。
         */
        TcpSockHandler h = sock.handler();
        if (h != null) {
            try {
                h.onReset(errException(err));
            } catch (Throwable ignore) {
                // 保护:用户 handler 异常不影响 reset 流程
            }
        }

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

    private static int sequenceCheck(TcpSock sock, TcpPacketBuf pkt) {
        int seq = pkt.tcpSeq();
        int segLen = pkt.tcpPayloadLength() + (pkt.isSyn() ? 1 : 0) + (pkt.isFin() ? 1 : 0);
        int endSeq = seq + segLen;
        int rcvWup = sock.receiver().rcvWup();
        int rcvNxt = sock.receiver().rcvNxt();
        int rcvWndEnd = rcvNxt + sock.receiver().receiveWindow();

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

    private static boolean resetCheck(TcpSock sock, TcpPacketBuf pkt) {
        if (pkt.tcpSeq() != sock.receiver().rcvNxt() - 1) {
            return false;
        }
        TcpConnectionState s = sock.state();
        return s == TcpConnectionState.CLOSE_WAIT
                || s == TcpConnectionState.LAST_ACK
                || s == TcpConnectionState.CLOSING;
    }
}

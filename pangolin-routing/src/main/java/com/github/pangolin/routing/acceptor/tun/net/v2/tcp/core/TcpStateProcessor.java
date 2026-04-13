package com.github.pangolin.routing.acceptor.tun.net.v2.tcp.core;

import com.github.pangolin.routing.acceptor.tun.net.handler.support.TcpPacketBuf;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.close.TcpCloseMachine;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.connection.TcpConnection;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.established.TcpAckHandler;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.established.TcpSegmentValidator;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.established.TcpSegmentValidator.ValidateResult;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.internal.TcpSequence;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import org.slf4j.Logger;

import static com.github.pangolin.routing.acceptor.tun.net.handler.tcp.util.TcpLogUtils.logFormat;

/**
 * Unified TCP state pre-processing path, modelled after Linux tcp_rcv_state_process:
 * flag gate -> validate incoming -> ack gate.
 */
public final class TcpStateProcessor {

    public static final TcpStateProcessor INSTANCE = new TcpStateProcessor();

    private TcpStateProcessor() {}

    public enum AckResult {
        NONE,
        OLD_OR_DUP,
        NEW_DATA_ACKED,
        INVALID_FUTURE_ACK
    }

    /**
     * Common pre-check for ESTABLISHED and close states.
     *
     * @return true if packet is fully handled (or dropped) and caller should return.
     */
    public boolean preProcess(ChannelHandlerContext ctx,
                              TcpConnection conn,
                              TcpPacketBuf pkt,
                              ChannelPromise closePromise,
                              Logger log,
                              String phase) {
        if (!pkt.isAck() && !pkt.isRst() && !pkt.isSyn()) {
            log.warn(logFormat("[TCP] [RCV]", pkt, "Connection reset: Invalid TCP flag(!ACK, !RST, !SYN)"));
            return true;
        }

        ValidateResult vr = TcpSegmentValidator.INSTANCE.validateIncoming(conn, pkt);
        if (vr == ValidateResult.PASS) {
            return false;
        }
        if (vr == ValidateResult.RESET) {
            log.debug("[TCP] [{}] RST accepted (seq==RCV.NXT) — abortive close", phase);
            TcpCloseMachine.abortiveClose(ctx, conn, closePromise);
        }
        return true;
    }

    /**
     * Common ACK gate roughly matching Linux tcp_ack return handling.
     */
    public AckResult processAck(TcpConnection conn, TcpPacketBuf pkt) {
        if (!pkt.isAck()) return AckResult.NONE;

        int ack = pkt.tcpAckNum();
        if (TcpSequence.after(ack, conn.sndNxt())) {
            // Invalid future ACK: challenge ACK then drop this segment.
            TcpSegmenter.INSTANCE.sendAck(conn);
            return AckResult.INVALID_FUTURE_ACK;
        }

        boolean advanced = TcpSequence.after(ack, conn.sndUna());
        TcpAckHandler.INSTANCE.onAck(conn, pkt);
        return advanced ? AckResult.NEW_DATA_ACKED : AckResult.OLD_OR_DUP;
    }
}

package com.github.pangolin.routing.acceptor.tun.net.v2.tcp.close;

import com.github.pangolin.routing.acceptor.tun.net.handler.support.TcpPacketBuf;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.connection.TcpConnection;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.connection.TcpConnectionState;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.core.TcpAckProcessor;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.core.TcpSegmenter;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.established.TcpSegmentValidator;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.internal.TcpConstants;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.timer.TcpTimerScheduler;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.timer.TimerType;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import io.netty.channel.SimpleChannelInboundHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Active close state machine (RFC 9293 §3.9).
 * Manages FIN_WAIT_1 → FIN_WAIT_2 → TIME_WAIT and the simultaneous-close path.
 *
 * <p>State transitions handled:
 * <pre>
 * FIN_WAIT_1 + ACK (our FIN's ACK)   → FIN_WAIT_2, schedule FIN_WAIT_2_TIMEOUT
 * FIN_WAIT_1 + FIN (simultaneous)    → CLOSING, send ACK
 * FIN_WAIT_1 + FIN+ACK               → TIME_WAIT, send ACK, schedule 2MSL
 * FIN_WAIT_2 + FIN                   → TIME_WAIT, send ACK, schedule 2MSL
 * FIN_WAIT_2 + FIN_WAIT_2_TIMEOUT    → TIME_WAIT (RFC 9293 §3.9.1)
 * CLOSING    + ACK                   → TIME_WAIT, schedule 2MSL
 * TIME_WAIT  expires                 → channel.close() → registry.deregister()
 * </pre>
 */
public final class TcpActiveCloseHandler extends SimpleChannelInboundHandler<TcpPacketBuf> {

    private static final Logger log = LoggerFactory.getLogger(TcpActiveCloseHandler.class);

    private final TcpConnection conn;
    private final ChannelPromise closePromise;

    public TcpActiveCloseHandler(TcpConnection conn, ChannelPromise closePromise) {
        super(false);
        this.conn         = conn;
        this.closePromise = closePromise;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, TcpPacketBuf pkt) {
        // RST: validate sequence before closing (RFC 9293 §3.5.2)
        if (pkt.isRst()) {
            if (TcpSegmentValidator.isRstAcceptable(conn, pkt)) {
                log.debug("[TCP] [ACTIVE-CLOSE] RST received — closing");
                ctx.channel().close(closePromise);
            }
            return;
        }

        // Process ACK in all active-close states: advances SND.UNA, samples RTT, updates CC
        if (pkt.isAck()) {
            TcpAckProcessor.INSTANCE.onAck(conn, pkt);
        }

        TcpConnectionState state = conn.state();

        if (state == TcpConnectionState.FIN_WAIT_1) {
            handleFinWait1(ctx, pkt);
        } else if (state == TcpConnectionState.FIN_WAIT_2) {
            handleFinWait2(ctx, pkt);
        } else if (state == TcpConnectionState.CLOSING) {
            handleClosing(ctx, pkt);
        }
        // TIME_WAIT: silently discard all packets (timer will close)
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        conn.close();
        ctx.fireChannelInactive();
    }

    // ── FIN_WAIT_1 ───────────────────────────────────────────────────────

    private void handleFinWait1(ChannelHandlerContext ctx, TcpPacketBuf pkt) {
        if (pkt.isFin() && pkt.isAck()) {
            // Simultaneous close: FIN+ACK received — jump to TIME_WAIT
            conn.rcvNxt(conn.rcvNxt() + 1);
            conn.state(TcpConnectionState.TIME_WAIT);
            TcpSegmenter.INSTANCE.sendAck(conn);
            schedule2Msl(ctx);
            log.debug("[TCP] [ACTIVE-CLOSE] FIN+ACK in FIN_WAIT_1 → TIME_WAIT");
        } else if (pkt.isFin()) {
            // Simultaneous close: only FIN — go to CLOSING
            conn.rcvNxt(conn.rcvNxt() + 1);
            conn.state(TcpConnectionState.CLOSING);
            TcpSegmenter.INSTANCE.sendAck(conn);
            log.debug("[TCP] [ACTIVE-CLOSE] FIN in FIN_WAIT_1 → CLOSING");
        } else if (pkt.isAck()) {
            // Our FIN was ACK'd — move to FIN_WAIT_2
            conn.state(TcpConnectionState.FIN_WAIT_2);
            scheduleFin2Timeout(ctx);
            log.debug("[TCP] [ACTIVE-CLOSE] ACK in FIN_WAIT_1 → FIN_WAIT_2");
        }
    }

    // ── FIN_WAIT_2 ───────────────────────────────────────────────────────

    private void handleFinWait2(ChannelHandlerContext ctx, TcpPacketBuf pkt) {
        if (pkt.isFin()) {
            conn.rcvNxt(conn.rcvNxt() + 1);
            conn.state(TcpConnectionState.TIME_WAIT);
            TcpSegmenter.INSTANCE.sendAck(conn);
            schedule2Msl(ctx);
            log.debug("[TCP] [ACTIVE-CLOSE] FIN in FIN_WAIT_2 → TIME_WAIT");
        }
    }

    // ── CLOSING ──────────────────────────────────────────────────────────

    private void handleClosing(ChannelHandlerContext ctx, TcpPacketBuf pkt) {
        if (pkt.isAck()) {
            conn.state(TcpConnectionState.TIME_WAIT);
            schedule2Msl(ctx);
            log.debug("[TCP] [ACTIVE-CLOSE] ACK in CLOSING → TIME_WAIT");
        }
    }

    // ── Timers ────────────────────────────────────────────────────────────

    private void scheduleFin2Timeout(ChannelHandlerContext ctx) {
        TcpTimerScheduler.INSTANCE.scheduleKeepalive(conn, TcpConstants.FIN_WAIT_2_TIMEOUT_MS,
                () -> {
                    if (conn.state() == TcpConnectionState.FIN_WAIT_2) {
                        log.debug("[TCP] [ACTIVE-CLOSE] FIN_WAIT_2 timeout → TIME_WAIT");
                        conn.state(TcpConnectionState.TIME_WAIT);
                        schedule2Msl(ctx);
                    }
                });
    }

    private void schedule2Msl(ChannelHandlerContext ctx) {
        // Cancel FIN_WAIT_2 timer if running
        TcpTimerScheduler.INSTANCE.cancelKeepalive(conn);
        TcpTimerScheduler.INSTANCE.scheduleKeepalive(conn, TcpConstants.TIME_WAIT_MS, () -> {
            log.debug("[TCP] [ACTIVE-CLOSE] 2MSL expired — closing channel");
            ctx.channel().close(closePromise);
        });
    }
}

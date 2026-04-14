package com.github.pangolin.routing.acceptor.tun.net.v2.tcp.core;

import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.close.TcpCloseMachine;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.connection.TcpConnection;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class TcpInputOps {
    // ── errno constants used by tcp_reset ────────────────────────────────
    /** Linux {@code ECONNRESET} (104) — connection reset by peer. */
    private static final int ECONNRESET   = 104;
    /** Linux {@code ECONNREFUSED} (111) — connection refused; set when RST arrives in SYN_SENT. */
    private static final int ECONNREFUSED = 111;
    /** Linux {@code EPIPE} (32) — broken pipe; set when RST arrives in CLOSE_WAIT. */
    private static final int EPIPE        = 32;


    private static String errName(int err) {
        switch (err) {
            case ECONNREFUSED: return "ECONNREFUSED";
            case EPIPE:        return "EPIPE";
            default:           return "ECONNRESET";
        }
    }

    /**
     * Mirrors Linux {@code tcp_reset()}: resolve the socket error code, report it,
     * then trigger abortive close (analogous to {@code tcp_done()}).
     *
     * <ul>
     *   <li>{@code TCP_SYN_SENT}  → {@code ECONNREFUSED} (peer rejected active open)</li>
     *   <li>{@code CLOSE_WAIT}    → {@code EPIPE} (app still writing after remote FIN + RST)</li>
     *   <li>{@code TCP_CLOSED}    → skip (already closed, nothing to do)</li>
     *   <li>default               → {@code ECONNRESET}</li>
     * </ul>
     *
     * <p>Called from within {@link #tcp_validate_incoming} so the caller only inspects
     * the boolean return value — mirroring how Linux calls {@code tcp_reset()} internally
     * before returning {@code false}.
     *
     * @see <a href="https://elixir.bootlin.com/linux/latest/source/net/ipv4/tcp_input.c">tcp_reset</a>
     */
    public static void tcp_reset(ChannelHandlerContext ctx, TcpConnection conn, ChannelPromise closePromise) {
        final int err;
        switch (conn.state()) {
            case TCP_SYN_SENT: err = ECONNREFUSED; break;
            case CLOSE_WAIT:   err = EPIPE;        break;
            case TCP_CLOSED:   return;             // already closed — skip
            default:           err = ECONNRESET;   break;
        }
        log.debug("[TCP] [{}] RST — err={}", conn.state().name(), errName(err));
        TcpCloseMachine.abortiveClose(ctx, conn, closePromise);
    }

}

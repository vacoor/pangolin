package com.github.pangolin.routing.acceptor.tun.net.v2.tcp.core;

import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.connection.TcpConnection;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.connection.TcpConnectionState;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.internal.TcpConstants;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.ng.TcpMultiplexer.TcpSock;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketException;

public class TcpInput {
    private static final Logger log = LoggerFactory.getLogger(TcpInput.class);
    // ── errno constants used by tcp_reset ────────────────────────────────
    /** Linux {@code ECONNRESET} (104) — connection reset by peer. */
    private static final int ECONNRESET   = 104;
    /** Linux {@code ECONNREFUSED} (111) — connection refused; set when RST arrives in SYN_SENT. */
    private static final int ECONNREFUSED = 111;
    /** Linux {@code EPIPE} (32) — broken pipe; set when RST arrives in CLOSE_WAIT. */
    private static final int EPIPE        = 32;

    /**
     * Mirrors Linux {@code tcp_reset()} (tcp_input.c).
     *
     * <p>Linux sequence and Netty mapping:
     * <pre>
     *   trace_tcp_receive_reset(sk)     → log.debug(...)
     *   sk->sk_err = err                → conn.skErr(err)
     *   smp_wmb()                       → no-op (single EventLoop thread)
     *   tcp_done(sk)                    → tcp_done(ctx, conn, closePromise)
     *   sk->sk_error_report(sk)         → ctx.fireExceptionCaught(errException(err))
     * </pre>
     *
     * <p><b>Order: tcp_done BEFORE sk_error_report</b> — Linux closes the socket first, then
     * wakes blocked waiters. In Netty: {@code doClose()} runs synchronously (channel becomes
     * inactive), but {@code channelInactive} is queued asynchronously; the pipeline is still
     * intact when {@code fireExceptionCaught} fires in the same EventLoop task.
     *
     * <p><b>No isActive() guard on fireExceptionCaught</b>: Linux's {@code SOCK_DEAD} flag is
     * set only when all userspace fd references are gone — not by {@code tcp_done} itself.
     * After {@code tcp_done}, {@code sk_error_report} is still called unconditionally (modulo
     * the SOCK_DEAD check). In Netty, since {@code channelUnregistered} has not yet fired,
     * pipeline handlers remain reachable and the exception can be delivered.
     *
     * <p>Error code by state:
     * <ul>
     *   <li>{@code TCP_SYN_SENT} → {@code ECONNREFUSED}</li>
     *   <li>{@code CLOSE_WAIT}   → {@code EPIPE}</li>
     *   <li>{@code TCP_CLOSED}   → skip (already closed)</li>
     *   <li>default              → {@code ECONNRESET}</li>
     * </ul>
     *
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_input.c#L4846">tcp_reset</a>
     */
    public static void tcp_reset(ChannelHandlerContext ctx, TcpConnection conn, ChannelPromise closePromise) {
        // trace_tcp_receive_reset(sk)
        log.debug("[TCP] [{}] RST received", conn.state().name());

        // sk->sk_err = ...
        final int err;
        switch (conn.state()) {
            case TCP_SYN_SENT: err = ECONNREFUSED; break;
            case CLOSE_WAIT:   err = EPIPE;        break;
            case TCP_CLOSED:   return;             // already closed — skip
            default:           err = ECONNRESET;   break;
        }
        tcp_done_with_error(ctx, conn, err, closePromise);
    }

    public static void tcp_reset(ChannelHandlerContext ctx, TcpSock sock, ChannelPromise closePromise) {
        if (sock == null || !sock.hasConnection()) {
            return;
        }
        log.debug("[TCP] [{}] RST received", sock.state().name());
        final int err;
        switch (sock.state()) {
            case TCP_SYN_SENT: err = ECONNREFUSED; break;
            case CLOSE_WAIT:   err = EPIPE;        break;
            case TCP_CLOSED:   return;
            default:           err = ECONNRESET;   break;
        }
        tcp_done_with_error(ctx, sock, err, closePromise);
    }

    public static void tcp_done_with_error(ChannelHandlerContext ctx, TcpConnection conn, int err, ChannelPromise closePromise) {
        conn.skErr(err);

        // smp_wmb() — no-op: Netty EventLoop provides single-thread sequential consistency.

        // tcp_done(sk) — close first, matching Linux order.
        tcp_done(ctx, conn, closePromise);

        // sk->sk_error_report(sk) — notify pipeline AFTER tcp_done, matching Linux order.
        // After tcp_done() / doClose(): active=false, but pipeline is still intact because
        // channelInactive fires asynchronously in the next EventLoop task.
        // Linux SOCK_DEAD is set only when all userspace fd references are gone — NOT by
        // tcp_done itself — so sk_error_report is still called after tcp_done.
        // In Netty: channelUnregistered has not fired yet, so pipeline handlers are reachable.
        ctx.fireExceptionCaught(errException(err));
    }

    public static void tcp_done_with_error(ChannelHandlerContext ctx, TcpSock sock, int err, ChannelPromise closePromise) {
        if (sock == null || !sock.hasConnection()) {
            return;
        }
        sock.skErr(err);
        tcp_done(ctx, sock, closePromise);
        ctx.fireExceptionCaught(errException(err));
    }

    public static ChannelFuture tcp_done(ChannelHandlerContext ctx, TcpConnection conn, ChannelPromise closePromise) {
        conn.state(TcpConnectionState.TCP_CLOSED);
        conn.skShutdown(TcpConstants.SHUTDOWN_MASK);
        conn.close();
        if (closePromise != null && !closePromise.isDone()) {
            closePromise.trySuccess();
        }
        return null;
    }

    public static ChannelFuture tcp_done(ChannelHandlerContext ctx, TcpSock sock, ChannelPromise closePromise) {
        if (sock == null || !sock.hasConnection()) {
            return null;
        }
        sock.state(TcpConnectionState.TCP_CLOSED);
        sock.skShutdown(TcpConstants.SHUTDOWN_MASK);
        sock.close();
        if (closePromise != null && !closePromise.isDone()) {
            closePromise.trySuccess();
        }
        return null;
    }

    /** Maps an errno constant to the closest Java exception. */
    private static Exception errException(int err) {
        switch (err) {
            case ECONNREFUSED: return new ConnectException("Connection refused");
            case EPIPE:        return new IOException("Broken pipe");
            default:           return new SocketException("Connection reset");
        }
    }

}

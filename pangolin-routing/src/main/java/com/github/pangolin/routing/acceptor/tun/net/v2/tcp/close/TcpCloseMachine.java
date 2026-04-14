package com.github.pangolin.routing.acceptor.tun.net.v2.tcp.close;

import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.connection.TcpConnection;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.connection.TcpConnectionState;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.core.TcpSegmenter;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.internal.TcpConstants;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;

/**
 * Unified close-path coordinator.
 *
 * <p>Design mirrors Linux:
 * <ul>
 *   <li>Graceful close: application close -> send FIN and enter FIN_WAIT_1/LAST_ACK.</li>
 *   <li>Abortive close: RST accepted / fatal error -> close immediately, no FIN.</li>
 * </ul>
 */
public final class TcpCloseMachine {

    private TcpCloseMachine() {}

    /**
     * Begin graceful passive-side close (CLOSE_WAIT -> LAST_ACK).
     */
    public static void beginLastAckClose(TcpConnection conn) {
        conn.addShutdown(TcpConstants.SEND_SHUTDOWN);
        conn.state(TcpConnectionState.LAST_ACK);
        TcpSegmenter.INSTANCE.sendPending(conn);
        TcpSegmenter.INSTANCE.sendFin(conn);
    }

    /**
     * Abortive close: do not send FIN, close channel immediately.
     */
    public static ChannelFuture abortiveClose(ChannelHandlerContext ctx, TcpConnection conn, ChannelPromise promise) {
        conn.skShutdown(TcpConstants.SHUTDOWN_MASK);
        if (promise != null) {
            return ctx.close(promise);
        } else {
            return ctx.close();
        }
    }

}

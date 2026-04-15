package com.github.pangolin.routing.acceptor.tun.net.v2.tcp.handshake;

import com.github.pangolin.routing.acceptor.tun.net.handler.support.TcpPacketBuf;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.connection.TcpConnection;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.connection.TcpConnectionState;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.established.TcpEstablishedHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * TCP handshake pipeline stage (Netty handler on Worker EventLoop).
 *
 * <p>Processes SYN and ACK packets to complete the 3-way handshake.
 * On completion, replaces itself in the pipeline with {@link TcpEstablishedHandler}.
 *
 * <p>SYN retransmits are handled idempotently: the same {@link TcpHandshaker} is reused.
 */
public final class TcpHandshakeHandler extends SimpleChannelInboundHandler<TcpPacketBuf> {

    private static final Logger log = LoggerFactory.getLogger(TcpHandshakeHandler.class);

    private final TcpHandshakerFactory factory;
    private TcpHandshaker handshaker;

    public TcpHandshakeHandler(TcpHandshakerFactory factory) {
        super(true);   // autoRelease=true: release pkt after channelRead0 returns
        this.factory = factory;
    }

    /**
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_input.c#tcp_conn_request">tcp_conn_request</a>
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_minisocks.c#L660">tcp_check_req</a>
     */
    @Override
    protected void channelRead0(ChannelHandlerContext ctx, TcpPacketBuf pkt) {
        if (handshaker == null) {
            /*-
             * TCP_LISTEN - <SYN> -> TCP_NEW_SYN_RECV (req sock).
             *
             * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_input.c#tcp_conn_request">tcp_conn_request</a>
             */
            // ── First packet: must be SYN (TcpMultiplexHandler already filters non-SYN) ──
            // Defensive guard in case a stray packet arrives before the SYN is processed.
            if (!pkt.isSyn() || pkt.isFin()) return;

            // sk_acceptq_is_full -> drop

            handshaker = factory.newHandshaker(pkt);
            handshaker.handshake(ctx.channel(), pkt);
            return;
        }

        /*-
         * TCP_NEW_SYN_RECV - <ACK> -> TCP_SYN_RECV (≈ tcp_check_req).
         *
         * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_minisocks.c#L660">tcp_check_req</a>
         */
        // ── All subsequent packets — delegate to handshaker (≈ tcp_check_req) ──
        // finishHandshake() handles RST, SYN retransmit, and the final ACK in one place.
        final TcpConnection nsk = handshaker.finishHandshake(ctx.channel(), pkt);
        if (null == nsk) {
            // RST or SYN retransmit handled inside tcp_check_req — drop packet
            return;
        }

        log.debug("[TCP] [HANDSHAKE] 3WH complete — switching to established handler");
        handshaker = null;
        ctx.pipeline().replace(this, "established", new TcpEstablishedHandler(nsk));
        ctx.pipeline().fireUserEventTriggered(TcpHandshakeCompletedEvent.INSTANCE);
        // Fire the ACK into the newly installed handler: TCP allows data to be piggybacked
        // on the final ACK (RFC 9293 §3.4). Without this, that data would be silently dropped.
        //
        // MUST use pipeline().fireChannelRead(), NOT ctx.fireChannelRead().
        // After replace(), `ctx` is the removed handler's context; its `next` pointer
        // still points to the handler that was after TcpHandshakeHandler BEFORE the
        // replace (i.e., SimpleChannelInboundHandler), skipping TcpEstablishedHandler.
        // pipeline().fireChannelRead() starts from HeadContext and correctly reaches
        // TcpEstablishedHandler at its new position.
        //
        // autoRelease=true will release pkt once after channelRead0 returns.
        // TcpEstablishedHandler.channelRead() also releases pkt once.
        // Retain here so the net refcount change is zero when both releases fire.
        /*-
         * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_minisocks.c#L992">tcp_child_process</a>
         */
        pkt.retain();
        ctx.pipeline().fireChannelRead(pkt);
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        // Channel closed before handshake completed — cancel the SYN-ACK retransmit timer.
        cancelHandshaker();
        ctx.fireChannelInactive();
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        log.warn("[TCP] [HANDSHAKE] Exception — closing connection", cause);
        cancelHandshaker();
        ctx.channel().close();
    }

    private void cancelHandshaker() {
        if (handshaker != null) {
            handshaker.cancelRetransmitTimer();
            handshaker = null;
        }
    }
}

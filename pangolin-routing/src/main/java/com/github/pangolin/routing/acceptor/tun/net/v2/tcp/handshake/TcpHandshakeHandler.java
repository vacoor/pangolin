package com.github.pangolin.routing.acceptor.tun.net.v2.tcp.handshake;

import com.github.pangolin.routing.acceptor.tun.net.handler.support.TcpPacketBuf;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.connection.TcpConnection;
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
    private       TcpHandshaker        handshaker;

    public TcpHandshakeHandler(TcpHandshakerFactory factory) {
        super(false);   // autoRelease=false: TcpPacketBuf lifetime is managed by caller
        this.factory = factory;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, TcpPacketBuf pkt) {
        if (pkt.isRst()) {
            log.debug("[TCP] [HANDSHAKE] RST received during handshake — closing");
            ctx.channel().close();
            return;
        }

        if (pkt.isSyn() && !pkt.isAck()) {
            // SYN (or retransmitted SYN)
            if (handshaker == null) {
                handshaker = factory.newHandshaker(pkt);
            }
            handshaker.handshake(ctx.channel(), pkt);
            return;
        }

        if (pkt.isAck() && handshaker != null) {
            // Final ACK of 3-way handshake
            TcpConnection conn = handshaker.finishHandshake(ctx.channel(), pkt);
            if (conn != null) {
                log.debug("[TCP] [HANDSHAKE] 3WH complete — switching to established handler");
                ctx.pipeline().replace(this, "established", new TcpEstablishedHandler(conn));
                // Fire the establishing packet into the new handler in case it carries data
                // (simultaneous data with ACK). TcpEstablishedHandler handles gracefully.
            }
        }
        // All other packets during handshake are silently dropped.
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        log.warn("[TCP] [HANDSHAKE] Exception — closing connection", cause);
        ctx.channel().close();
    }
}

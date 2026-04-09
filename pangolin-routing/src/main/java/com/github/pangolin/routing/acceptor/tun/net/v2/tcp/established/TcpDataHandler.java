package com.github.pangolin.routing.acceptor.tun.net.v2.tcp.established;

import com.github.pangolin.routing.acceptor.tun.net.handler.support.TcpPacketBuf;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.connection.TcpConnection;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;

/**
 * RFC 9293 data reception: enqueues payload into {@code TcpReceiveBuffer} (with OFO handling)
 * and propagates in-order data to the application via {@code fireChannelRead}.
 */
public final class TcpDataHandler {

    public static final TcpDataHandler INSTANCE = new TcpDataHandler();

    private TcpDataHandler() {}

    /**
     * Process the payload of an incoming data segment.
     *
     * @param ctx  channel handler context (for forwarding data upstream)
     * @param conn the TCP connection state
     * @param pkt  the incoming segment
     */
    public void onData(ChannelHandlerContext ctx, TcpConnection conn, TcpPacketBuf pkt) {
        ByteBuf payload = pkt.tcpPayloadSlice();
        if (!payload.isReadable()) {
            return;
        }

        // Retain a copy for the receive buffer (slice shares refcount with pkt)
        ByteBuf copy = payload.copy();
        int newRcvNxt = conn.receiveBuffer().offer(pkt.tcpSeq(), conn.rcvNxt(), copy);
        conn.rcvNxt(newRcvNxt);

        // Forward all available in-order data to upstream / application
        if (conn.receiveBuffer().isReadable()) {
            ByteBuf data = conn.receiveBuffer().readAll();
            ctx.fireChannelRead(data);
        }
    }
}

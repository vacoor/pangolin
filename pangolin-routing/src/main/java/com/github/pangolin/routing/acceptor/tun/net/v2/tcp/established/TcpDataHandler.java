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
        ByteBuf data = onData(conn, pkt);
        if (data != null) {
            ctx.fireChannelRead(data);
        }
    }

    public ByteBuf onData(TcpConnection conn, TcpPacketBuf pkt) {
        ByteBuf payload = pkt.tcpPayloadSlice();
        if (!payload.isReadable()) {
            return null;
        }

        // retainedSlice shares the underlying buffer with pkt and adds a +1 refcount,
        // so the receive buffer owns one reference independently of pkt's lifetime.
        // Avoids the full data copy that copy() would perform.
        ByteBuf segment = payload.retainedSlice();
        int newRcvNxt = conn.receiveBuffer().offer(pkt.tcpSeq(), conn.rcvNxt(), segment);
        conn.rcvNxt(newRcvNxt);

        if (conn.receiveBuffer().isReadable()) {
            return conn.receiveBuffer().readAll();
        }
        return null;
    }
}

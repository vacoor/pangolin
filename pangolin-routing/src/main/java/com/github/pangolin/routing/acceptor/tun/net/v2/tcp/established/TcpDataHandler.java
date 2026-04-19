package com.github.pangolin.routing.acceptor.tun.net.v2.tcp.established;

import com.github.pangolin.routing.acceptor.tun.net.handler.support.TcpPacketBuf;
import com.github.pangolin.routing.acceptor.tun.net.handler.tcp.util.TcpUtils;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.connection.TcpReceiveBuffer;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.ng.TcpMultiplexer.TcpSock;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;

public final class TcpDataHandler {

    public static final TcpDataHandler INSTANCE = new TcpDataHandler();

    private TcpDataHandler() {
    }

    public void onData(ChannelHandlerContext ctx, TcpSock sock, TcpPacketBuf pkt) {
        ByteBuf data = onData(sock, pkt);
        if (data != null) {
            ctx.fireChannelRead(data);
        }
    }

    /**
     * 将 {@code pkt} 中的有效载荷交付给 {@link TcpReceiveBuffer};若当前段位于 RCV.NXT
     * 对齐处并含 FIN,或其填补后解锁了 OFO 中的 FIN,则由调用方通过
     * {@link TcpReceiveBuffer.OfferResult#finDelivered} 感知并触发 {@code tcp_fin}。
     *
     * <p>同 v1:{@code tcp_queue_rcv} 只负责入读缓冲与推进 {@code rcv_nxt},
     * 状态机迁移留给上层 {@code queue_and_out}。
     */
    public ByteBuf onData(TcpSock sock, TcpPacketBuf pkt) {
        final int seq        = pkt.tcpSeq();
        final int endSeq     = TcpUtils.determineEndSeq(pkt);
        final boolean fin    = pkt.isFin();
        final int payloadLen = pkt.tcpPayloadLength();

        final ByteBuf payload = pkt.tcpPayloadSlice();
        final ByteBuf segment = payloadLen > 0
                ? payload.retainedSlice()
                : Unpooled.EMPTY_BUFFER;

        TcpReceiveBuffer.OfferResult r = sock.receiveBuffer()
                .offer(seq, endSeq, sock.rcvNxt(), segment, fin);
        sock.rcvNxt(r.rcvNxt);

        if (sock.receiveBuffer().isReadable()) {
            return sock.receiveBuffer().readAll();
        }
        return null;
    }
}

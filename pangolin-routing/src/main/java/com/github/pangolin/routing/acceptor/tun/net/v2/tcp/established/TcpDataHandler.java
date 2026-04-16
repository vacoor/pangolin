package com.github.pangolin.routing.acceptor.tun.net.v2.tcp.established;

import com.github.pangolin.routing.acceptor.tun.net.handler.support.TcpPacketBuf;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.ng.TcpMultiplexer.TcpSock;
import io.netty.buffer.ByteBuf;
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

    public ByteBuf onData(TcpSock sock, TcpPacketBuf pkt) {
        ByteBuf payload = pkt.tcpPayloadSlice();
        if (!payload.isReadable()) {
            return null;
        }

        ByteBuf segment = payload.retainedSlice();
        int newRcvNxt = sock.receiveBuffer().offer(pkt.tcpSeq(), sock.rcvNxt(), segment);
        sock.rcvNxt(newRcvNxt);

        if (sock.receiveBuffer().isReadable()) {
            return sock.receiveBuffer().readAll();
        }
        return null;
    }
}

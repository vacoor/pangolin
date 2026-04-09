package com.github.pangolin.routing.acceptor.tun.net.v2.tcp.core;

import com.github.pangolin.routing.acceptor.tun.net.handler.tcp.util.TcpOptionCodec;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.connection.TcpConnection;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.internal.FourTuple;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.pipeline.TcpConnectionChannel;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFuture;

/**
 * Builds and sends raw IPv4+TCP packets for an established connection.
 *
 * <p>All outbound packets are written to the connection's {@code TcpConnectionChannel},
 * which routes them via {@code doWrite()} → {@code parentCtx.write()} → TUN Channel pipeline.
 */
public final class TcpPacketBuilder {

    public static final TcpPacketBuilder INSTANCE = new TcpPacketBuilder();

    private TcpPacketBuilder() {}

    // ── Public send methods ───────────────────────────────────────────────

    /** Send a pure ACK (no data, no flags beyond ACK). */
    public ChannelFuture sendAck(TcpConnection conn) {
        return send(conn, conn.sndNxt(), conn.rcvNxt(), 0x10 /* ACK */, null);
    }

    /** Send data segment. Caller owns the payload buffer (not released by this method). */
    public ChannelFuture sendData(TcpConnection conn, int seq, ByteBuf payload) {
        return send(conn, seq, conn.rcvNxt(), 0x10 /* ACK */, payload);
    }

    /** Send data with PSH flag set. */
    public ChannelFuture sendDataPsh(TcpConnection conn, int seq, ByteBuf payload) {
        return send(conn, seq, conn.rcvNxt(), 0x18 /* PSH+ACK */, payload);
    }

    /** Send FIN+ACK. */
    public ChannelFuture sendFin(TcpConnection conn) {
        return send(conn, conn.sndNxt(), conn.rcvNxt(), 0x11 /* FIN+ACK */, null);
    }

    /** Send RST+ACK. */
    public ChannelFuture sendRstAck(TcpConnection conn) {
        return send(conn, conn.sndNxt(), conn.rcvNxt(), 0x14 /* RST+ACK */, null);
    }

    // ── Low-level builder ────────────────────────────────────────────────

    private ChannelFuture send(TcpConnection conn, int seq, int ack,
                                int tcpFlags, ByteBuf payload) {
        FourTuple ft = ((TcpConnectionChannel) conn.channel()).fourTuple();

        // Our address is the destination of the original incoming packet
        byte[] srcIp   = ft.dstAddrBytes();
        int    srcPort = ft.dstPort();
        byte[] dstIp   = ft.srcAddrBytes();
        int    dstPort = ft.srcPort();

        int window = conn.rcvWnd();
        if (conn.rcvWscale() > 0) {
            window >>= conn.rcvWscale();
        }
        if (window > 65535) window = 65535;
        if (window < 0)     window = 0;

        int payloadLen = (payload != null) ? payload.readableBytes() : 0;
        ByteBuf buf = buildRaw(srcIp, srcPort, dstIp, dstPort,
                               seq, ack, tcpFlags, window,
                               buildDataOptions(conn), payload, payloadLen);
        return conn.channel().writeAndFlush(buf);
    }

    /** Build a raw IPv4+TCP packet (no payload copy — uses {@code payload.slice()}). */
    public static ByteBuf buildRaw(byte[] srcIp, int srcPort,
                             byte[] dstIp, int dstPort,
                             int seq, int ack, int tcpFlags, int window,
                             byte[] options, ByteBuf payload, int payloadLen) {
        int optLen     = options != null ? options.length : 0;
        int tcpHdrLen  = 20 + optLen;
        int ipTotalLen = 20 + tcpHdrLen + payloadLen;

        ByteBuf buf = Unpooled.buffer(ipTotalLen);

        // IPv4 header
        int ipHdrStart = buf.writerIndex();
        buf.writeByte(0x45);
        buf.writeByte(0);
        buf.writeShort(ipTotalLen);
        buf.writeShort(0);
        buf.writeShort(0x4000);
        buf.writeByte(64);
        buf.writeByte(0x06);
        int ipCsumIdx = buf.writerIndex();
        buf.writeShort(0);
        buf.writeBytes(srcIp);
        buf.writeBytes(dstIp);

        // TCP header
        int tcpHdrStart = buf.writerIndex();
        buf.writeShort(srcPort);
        buf.writeShort(dstPort);
        buf.writeInt(seq);
        buf.writeInt(ack);
        buf.writeByte((tcpHdrLen / 4) << 4);
        buf.writeByte(tcpFlags);
        buf.writeShort(window);
        int tcpCsumIdx = buf.writerIndex();
        buf.writeShort(0);
        buf.writeShort(0);

        if (optLen > 0) buf.writeBytes(options);

        if (payload != null && payloadLen > 0) {
            buf.writeBytes(payload, payload.readerIndex(), payloadLen);
        }

        // Checksums
        buf.setShort(tcpCsumIdx, tcpChecksum(buf, srcIp, dstIp, tcpHdrStart, tcpHdrLen + payloadLen));
        buf.setShort(ipCsumIdx,  ipChecksum(buf, ipHdrStart, 20));

        return buf;
    }

    private static byte[] buildDataOptions(TcpConnection conn) {
        // Timestamps if enabled
        if (conn.timestampExt().isEnabled(conn)) {
            ByteBuf tmp = Unpooled.buffer(12);
            try {
                TcpOptionCodec.writeTimestampOption(tmp,
                        conn.timestampExt().buildTsval(conn) & 0xFFFFFFFFL,
                        0);  // tsecr = 0 for simplicity; full RFC7323 impl tracks ts_recent
                byte[] result = new byte[tmp.readableBytes()];
                tmp.readBytes(result);
                return result;
            } finally {
                tmp.release();
            }
        }
        return null;
    }

    // ── Checksum helpers ─────────────────────────────────────────────────

    static int tcpChecksum(ByteBuf buf, byte[] srcIp, byte[] dstIp,
                            int tcpStart, int tcpLen) {
        long sum = 0;
        sum += word(srcIp, 0); sum += word(srcIp, 2);
        sum += word(dstIp, 0); sum += word(dstIp, 2);
        sum += 6;               // protocol TCP
        sum += tcpLen;
        int i = tcpStart;
        int end = tcpStart + tcpLen;
        while (i + 1 < end) { sum += buf.getUnsignedShort(i); i += 2; }
        if (i < end) sum += (buf.getUnsignedByte(i) << 8);
        while (sum >> 16 != 0) sum = (sum & 0xFFFF) + (sum >> 16);
        return (int)(~sum & 0xFFFF);
    }

    static int ipChecksum(ByteBuf buf, int start, int len) {
        long sum = 0;
        for (int i = start; i < start + len; i += 2) sum += buf.getUnsignedShort(i);
        while (sum >> 16 != 0) sum = (sum & 0xFFFF) + (sum >> 16);
        return (int)(~sum & 0xFFFF);
    }

    private static int word(byte[] b, int off) {
        return ((b[off] & 0xFF) << 8) | (b[off + 1] & 0xFF);
    }
}

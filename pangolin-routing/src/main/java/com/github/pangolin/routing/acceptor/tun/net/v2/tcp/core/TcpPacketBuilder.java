package com.github.pangolin.routing.acceptor.tun.net.v2.tcp.core;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

/**
 * Stateless factory: assembles raw IPv4+TCP packets.
 *
 * <p>The single entry point is {@link #buildRaw}. All IO (writing to a channel) is
 * performed exclusively by {@link TcpOutput}.
 */
public final class TcpPacketBuilder {

    private TcpPacketBuilder() {}

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

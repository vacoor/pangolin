package com.github.pangolin.routing.acceptor.tun.net.handler.support;

import io.netty.buffer.ByteBuf;

/**
 * Abstract base for TCP-over-IP packet wrappers.
 * Extends {@link IpPacketBuf} with TCP header field accessors.
 * Concrete subclasses: {@link Tcp4PacketBuf} (IPv4), {@link Tcp6PacketBuf} (IPv6).
 */
public abstract class TcpPacketBuf extends IpPacketBuf {

    // TCP flag bits (byte 13 of TCP header, low 6 bits)
    public static final int TCP_FLAG_FIN = 0x01;
    public static final int TCP_FLAG_SYN = 0x02;
    public static final int TCP_FLAG_RST = 0x04;
    public static final int TCP_FLAG_PSH = 0x08;
    public static final int TCP_FLAG_ACK = 0x10;
    public static final int TCP_FLAG_URG = 0x20;

    private static final int TCP_MIN_HEADER_LEN = 20;

    protected TcpPacketBuf(final ByteBuf buf) {
        super(buf);
    }

    private int tcpBase() {
        return buf.readerIndex() + ipHeaderLen();
    }

    // ---- TCP header field accessors ----

    public int tcpSrcPort() {
        return buf.getUnsignedShort(tcpBase());
    }

    public int tcpDstPort() {
        return buf.getUnsignedShort(tcpBase() + 2);
    }

    public int tcpSeq() {
        return buf.getInt(tcpBase() + 4);
    }

    public int tcpAckNum() {
        return buf.getInt(tcpBase() + 8);
    }

    /** TCP header length in bytes, derived from data offset field. */
    public int tcpDataOffset() {
        return ((buf.getByte(tcpBase() + 12) >> 4) & 0xF) * 4;
    }

    public int tcpFlags() {
        return buf.getByte(tcpBase() + 13) & 0xFF;
    }

    public int tcpWindow() {
        return buf.getUnsignedShort(tcpBase() + 14);
    }

    // ---- TCP flag helpers ----

    public boolean isFin() { return (tcpFlags() & TCP_FLAG_FIN) != 0; }
    public boolean isSyn() { return (tcpFlags() & TCP_FLAG_SYN) != 0; }
    public boolean isRst() { return (tcpFlags() & TCP_FLAG_RST) != 0; }
    public boolean isPsh() { return (tcpFlags() & TCP_FLAG_PSH) != 0; }
    public boolean isAck() { return (tcpFlags() & TCP_FLAG_ACK) != 0; }
    public boolean isUrg() { return (tcpFlags() & TCP_FLAG_URG) != 0; }

    // ---- TCP options slice (no copy) ----

    /** Returns a derived slice of TCP options bytes; do NOT release (shares buf refcount). */
    public ByteBuf tcpOptionsSlice() {
        int optsLen = tcpDataOffset() - TCP_MIN_HEADER_LEN;
        if (optsLen <= 0) {
            return buf.slice(tcpBase() + TCP_MIN_HEADER_LEN, 0);
        }
        return buf.slice(tcpBase() + TCP_MIN_HEADER_LEN, optsLen);
    }

    // ---- TCP payload slice (no copy) ----

    public int tcpPayloadLength() {
        return buf.readableBytes() - ipHeaderLen() - tcpDataOffset();
    }

    /** Returns a derived slice of TCP payload; do NOT release (shares buf refcount). */
    public ByteBuf tcpPayloadSlice() {
        int payloadLen = tcpPayloadLength();
        if (payloadLen <= 0) {
            return buf.slice(tcpBase() + tcpDataOffset(), 0);
        }
        return buf.slice(tcpBase() + tcpDataOffset(), payloadLen);
    }
}

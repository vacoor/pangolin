package com.github.pangolin.routing.acceptor.tun.net.handler.support;

import io.netty.buffer.ByteBuf;

import java.net.InetAddress;
import java.net.UnknownHostException;

/**
 * Lightweight zero-copy wrapper around a raw IP packet ByteBuf.
 * All field accesses use absolute byte offsets (getXxx) without moving readerIndex.
 *
 * @see Ip4PacketBuf
 * @see Ip6PacketBuf
 */
public abstract class IpPacketBuf {

    public static final byte PROTO_TCP = 6;
    public static final byte PROTO_UDP = 17;

    // TCP flag bits (byte 13 of TCP header, low 6 bits)
    public static final int TCP_FLAG_FIN = 0x01;
    public static final int TCP_FLAG_SYN = 0x02;
    public static final int TCP_FLAG_RST = 0x04;
    public static final int TCP_FLAG_PSH = 0x08;
    public static final int TCP_FLAG_ACK = 0x10;
    public static final int TCP_FLAG_URG = 0x20;

    private static final int TCP_MIN_HEADER_LEN = 20;

    protected final ByteBuf buf;

    /** DNS-resolved destination address; stored as metadata, buf is never modified. */
    private InetAddress resolvedDstAddr;

    protected IpPacketBuf(ByteBuf buf) {
        this.buf = buf;
    }

    // ---- factory ----

    /**
     * Wrap buf that is already retained by the caller. IpPacketBuf takes ownership.
     */
    public static IpPacketBuf wrap(ByteBuf buf) {
        int version = (buf.getByte(buf.readerIndex()) >> 4) & 0xF;
        if (version == 4) return new Ip4PacketBuf(buf);
        if (version == 6) return new Ip6PacketBuf(buf);
        throw new IllegalArgumentException("Unsupported IP version: " + version);
    }

    /**
     * Retain buf once internally and create wrapper. Caller still owns their reference.
     */
    public static IpPacketBuf retainedWrap(ByteBuf buf) {
        int version = (buf.getByte(buf.readerIndex()) >> 4) & 0xF;
        if (version == 4) return new Ip4PacketBuf(buf.retain());
        if (version == 6) return new Ip6PacketBuf(buf.retain());
        throw new IllegalArgumentException("Unsupported IP version: " + version);
    }

    // ---- IP version ----

    public int version() {
        return (buf.getByte(buf.readerIndex()) >> 4) & 0xF;
    }

    // ---- subclass-specific IP header fields ----

    /** IP header length in bytes (IPv4: IHL field * 4; IPv6: fixed 40). */
    public abstract int ipHeaderLen();

    /** Upper-layer protocol number (TCP=6, UDP=17). */
    public abstract byte nextProto();

    /** TTL / Hop Limit. */
    public abstract int hopLimit();

    public abstract byte[] srcAddrBytes();

    public abstract byte[] dstAddrBytes();

    public abstract InetAddress srcAddr();

    public abstract InetAddress dstAddr();

    // ---- TCP field accessors (offset = readerIndex + ipHeaderLen()) ----

    private int tcpBase() {
        return buf.readerIndex() + ipHeaderLen();
    }

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

    /**
     * TCP header length in bytes, derived from data offset field.
     */
    public int tcpDataOffset() {
        return ((buf.getByte(tcpBase() + 12) >> 4) & 0xF) * 4;
    }

    public int tcpFlags() {
        return buf.getByte(tcpBase() + 13) & 0xFF;
    }

    public int tcpWindow() {
        return buf.getUnsignedShort(tcpBase() + 14);
    }

    public boolean isFin() {
        return (tcpFlags() & TCP_FLAG_FIN) != 0;
    }

    public boolean isSyn() {
        return (tcpFlags() & TCP_FLAG_SYN) != 0;
    }

    public boolean isRst() {
        return (tcpFlags() & TCP_FLAG_RST) != 0;
    }

    public boolean isPsh() {
        return (tcpFlags() & TCP_FLAG_PSH) != 0;
    }

    public boolean isAck() {
        return (tcpFlags() & TCP_FLAG_ACK) != 0;
    }

    public boolean isUrg() {
        return (tcpFlags() & TCP_FLAG_URG) != 0;
    }

    public boolean isTcp() {
        return nextProto() == PROTO_TCP;
    }

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
        int readable = buf.readableBytes();
        return readable - ipHeaderLen() - tcpDataOffset();
    }

    /** Returns a derived slice of TCP payload; do NOT release (shares buf refcount). */
    public ByteBuf tcpPayloadSlice() {
        int payloadLen = tcpPayloadLength();
        if (payloadLen <= 0) {
            return buf.slice(tcpBase() + tcpDataOffset(), 0);
        }
        return buf.slice(tcpBase() + tcpDataOffset(), payloadLen);
    }

    // ---- UDP field accessors (offset = readerIndex + ipHeaderLen()) ----

    private int udpBase() {
        return buf.readerIndex() + ipHeaderLen();
    }

    public int udpSrcPort() {
        return buf.getUnsignedShort(udpBase());
    }

    public int udpDstPort() {
        return buf.getUnsignedShort(udpBase() + 2);
    }

    public int udpLength() {
        return buf.getUnsignedShort(udpBase() + 4);
    }

    public int udpPayloadLength() {
        return udpLength() - 8;
    }

    /** Returns a derived slice of UDP payload; do NOT release (shares buf refcount). */
    public ByteBuf udpPayloadSlice() {
        int payloadLen = udpPayloadLength();
        if (payloadLen <= 0) {
            return buf.slice(udpBase() + 8, 0);
        }
        return buf.slice(udpBase() + 8, payloadLen);
    }

    // ---- DNS resolved address metadata ----

    public InetAddress resolvedDstAddr() {
        return resolvedDstAddr;
    }

    public IpPacketBuf resolvedDstAddr(InetAddress addr) {
        this.resolvedDstAddr = addr;
        return this;
    }

    // ---- raw buf ----

    public ByteBuf buf() {
        return buf;
    }

    public void release() {
        buf.release();
    }

    // ---- helpers ----

    protected byte[] readBytes(int absoluteOffset, int length) {
        byte[] bytes = new byte[length];
        buf.getBytes(absoluteOffset, bytes);
        return bytes;
    }

    protected InetAddress toInetAddress(String host, byte[] addr) {
        try {
            return InetAddress.getByAddress(host, addr);
        } catch (UnknownHostException e) {
            throw new IllegalStateException("Failed to create InetAddress", e);
        }
    }
}

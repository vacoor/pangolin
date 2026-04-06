package com.github.pangolin.routing.acceptor.tun.net.handler.support;

import io.netty.buffer.ByteBuf;
import io.netty.util.ReferenceCounted;

import java.net.InetAddress;
import java.net.UnknownHostException;

/**
 * Lightweight zero-copy wrapper around a raw IP packet ByteBuf.
 * All field accesses use absolute byte offsets (getXxx) without moving readerIndex.
 *
 * <p>Implements {@link ReferenceCounted} by delegating all reference counting to the
 * underlying {@link ByteBuf}. This allows Netty's {@code ReferenceCountUtil.release()}
 * and leak detector to work correctly when {@code IpPacketBuf} flows through a pipeline
 * as a channel message.
 *
 * <p>The factory ({@link #wrap}/{@link #retainedWrap}) dispatches to the most-specific
 * subclass based on IP version and next-protocol:
 * <ul>
 *   <li>IPv4 + TCP=6  → {@link Tcp4PacketBuf}</li>
 *   <li>IPv4 + UDP=17 → {@link Udp4PacketBuf}</li>
 *   <li>IPv4 + other  → {@link Ip4PacketBuf}</li>
 *   <li>IPv6 + TCP=6  → {@link Tcp6PacketBuf}</li>
 *   <li>IPv6 + other  → {@link Ip6PacketBuf}</li>
 * </ul>
 *
 * @see TcpPacketBuf
 * @see UdpPacketBuf
 */
public abstract class IpPacketBuf implements ReferenceCounted {

    public static final byte PROTO_TCP = 6;
    public static final byte PROTO_UDP = 17;

    protected final ByteBuf buf;

    /** DNS-resolved destination address; stored as metadata, buf is never modified. */
    private InetAddress resolvedDstAddr;

    protected IpPacketBuf(ByteBuf buf) {
        this.buf = buf;
    }

    // ---- factory ----

    /**
     * Wrap buf that is already retained by the caller. IpPacketBuf takes ownership.
     * Dispatches to the most-specific subclass based on IP version and next-protocol.
     */
    public static IpPacketBuf wrap(ByteBuf buf) {
        final int base = buf.readerIndex();
        final int version = (buf.getByte(base) >> 4) & 0xF;
        if (version == 4) {
            final byte proto = buf.getByte(base + 9);
            if (proto == PROTO_TCP) return new Tcp4PacketBuf(buf);
            if (proto == PROTO_UDP) return new Udp4PacketBuf(buf);
            return new Ip4PacketBuf(buf);
        }
        if (version == 6) {
            final byte proto = buf.getByte(base + 6);
            if (proto == PROTO_TCP) return new Tcp6PacketBuf(buf);
            return new Ip6PacketBuf(buf);
        }
        throw new IllegalArgumentException("Unsupported IP version: " + version);
    }

    /**
     * Retain buf once internally and create wrapper. Caller still owns their reference.
     * Dispatches to the most-specific subclass based on IP version and next-protocol.
     */
    public static IpPacketBuf retainedWrap(ByteBuf buf) {
        final int base = buf.readerIndex();
        final int version = (buf.getByte(base) >> 4) & 0xF;
        if (version == 4) {
            final byte proto = buf.getByte(base + 9);
            if (proto == PROTO_TCP) return new Tcp4PacketBuf(buf.retain());
            if (proto == PROTO_UDP) return new Udp4PacketBuf(buf.retain());
            return new Ip4PacketBuf(buf.retain());
        }
        if (version == 6) {
            final byte proto = buf.getByte(base + 6);
            if (proto == PROTO_TCP) return new Tcp6PacketBuf(buf.retain());
            return new Ip6PacketBuf(buf.retain());
        }
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

    // ---- DNS resolved address metadata ----

    public InetAddress resolvedDstAddr() {
        return resolvedDstAddr;
    }

    public IpPacketBuf resolvedDstAddr(InetAddress addr) {
        this.resolvedDstAddr = addr;
        return this;
    }

    // ---- ReferenceCounted (delegated to buf) ----

    @Override
    public int refCnt() {
        return buf.refCnt();
    }

    @Override
    public IpPacketBuf retain() {
        buf.retain();
        return this;
    }

    @Override
    public IpPacketBuf retain(int increment) {
        buf.retain(increment);
        return this;
    }

    @Override
    public IpPacketBuf touch() {
        buf.touch();
        return this;
    }

    @Override
    public IpPacketBuf touch(Object hint) {
        buf.touch(hint);
        return this;
    }

    @Override
    public boolean release() {
        return buf.release();
    }

    @Override
    public boolean release(int decrement) {
        return buf.release(decrement);
    }

    // ---- raw buf ----

    public ByteBuf buf() {
        return buf;
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

package com.github.pangolin.routing.acceptor.tun.net.codec;

import io.netty.buffer.ByteBuf;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;

/**
 * IPv6 specialization of {@link IpPacketBuf}.
 *
 * <pre>
 * IPv6 fixed header layout (offset from readerIndex, 40 bytes):
 *   Byte  0-3:  Version(4) + Traffic Class(8) + Flow Label(20)
 *   Byte  4-5:  Payload Length (not including 40B header)
 *   Byte  6:    Next Header (TCP=6)
 *   Byte  7:    Hop Limit
 *   Byte  8-23: Source Address (16 bytes)
 *   Byte 24-39: Destination Address (16 bytes)
 *   Byte 40+:   TCP header (assuming no extension headers)
 * </pre>
 *
 * <p>Note: IPv6 extension headers are not currently supported. This implementation
 * assumes Next Header is directly TCP(6). Extension header support is a future work item.
 */
public final class Ip6PacketBuf extends IpPacketBuf {

    private static final int IP6_HEADER_LEN = 40;

    Ip6PacketBuf(ByteBuf buf) {
        super(buf);
    }

    @Override
    public int ipHeaderLen() {
        // Fixed 40 bytes; extension headers not supported
        return IP6_HEADER_LEN;
    }

    @Override
    public byte nextProto() {
        return buf.getByte(buf.readerIndex() + 6);
    }

    @Override
    public int hopLimit() {
        return buf.getUnsignedByte(buf.readerIndex() + 7);
    }

    @Override
    public byte[] srcAddrBytes() {
        return readBytes(buf.readerIndex() + 8, 16);
    }

    @Override
    public byte[] dstAddrBytes() {
        return readBytes(buf.readerIndex() + 24, 16);
    }

    @Override
    public InetAddress srcAddr() {
        return toInetAddress(null, srcAddrBytes());
    }

    @Override
    public InetAddress dstAddr() {
        InetAddress resolved = resolvedDstAddr();
        return resolved != null ? resolved : toInetAddress(null, dstAddrBytes());
    }

    public int payloadLength() {
        return buf.getUnsignedShort(buf.readerIndex() + 4);
    }

    @Override
    protected InetAddress toInetAddress(String host, byte[] addr) {
        try {
            return host != null
                    ? InetAddress.getByAddress(host, addr)
                    : InetAddress.getByAddress(addr);
        } catch (UnknownHostException e) {
            throw new IllegalStateException("Failed to create Inet6Address", e);
        }
    }

    public Inet6Address srcAddr6() {
        return (Inet6Address) srcAddr();
    }

    public Inet6Address dstAddr6() {
        return (Inet6Address) dstAddr();
    }
}

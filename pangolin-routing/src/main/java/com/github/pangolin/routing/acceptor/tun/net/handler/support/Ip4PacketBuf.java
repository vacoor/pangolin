package com.github.pangolin.routing.acceptor.tun.net.handler.support;

import io.netty.buffer.ByteBuf;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.UnknownHostException;

/**
 * IPv4 specialization of {@link IpPacketBuf}.
 *
 * <pre>
 * IPv4 header layout (offset from readerIndex):
 *   Byte  0: Version(4) + IHL(4)
 *   Byte  1: DSCP/ECN
 *   Byte  2-3: Total Length
 *   Byte  4-5: Identification
 *   Byte  6-7: Flags + Fragment Offset
 *   Byte  8: TTL
 *   Byte  9: Protocol
 *   Byte 10-11: Header Checksum
 *   Byte 12-15: Source IP
 *   Byte 16-19: Destination IP
 * </pre>
 */
public final class Ip4PacketBuf extends IpPacketBuf {

    Ip4PacketBuf(ByteBuf buf) {
        super(buf);
    }

    @Override
    public int ipHeaderLen() {
        return (buf.getByte(buf.readerIndex()) & 0xF) * 4;
    }

    @Override
    public byte nextProto() {
        return buf.getByte(buf.readerIndex() + 9);
    }

    @Override
    public int hopLimit() {
        return buf.getUnsignedByte(buf.readerIndex() + 8);
    }

    @Override
    public byte[] srcAddrBytes() {
        return readBytes(buf.readerIndex() + 12, 4);
    }

    @Override
    public byte[] dstAddrBytes() {
        return readBytes(buf.readerIndex() + 16, 4);
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

    // ---- IPv4-specific fields ----

    public int totalLength() {
        return buf.getUnsignedShort(buf.readerIndex() + 2);
    }

    public short ipId() {
        return buf.getShort(buf.readerIndex() + 4);
    }

    public short ipFlags() {
        return buf.getShort(buf.readerIndex() + 6);
    }

    public byte tos() {
        return buf.getByte(buf.readerIndex() + 1);
    }

    @Override
    protected InetAddress toInetAddress(String host, byte[] addr) {
        try {
            return host != null
                    ? InetAddress.getByAddress(host, addr)
                    : InetAddress.getByAddress(addr);
        } catch (UnknownHostException e) {
            throw new IllegalStateException("Failed to create Inet4Address", e);
        }
    }

    public Inet4Address srcAddr4() {
        return (Inet4Address) srcAddr();
    }

    public Inet4Address dstAddr4() {
        return (Inet4Address) dstAddr();
    }
}

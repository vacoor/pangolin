package com.github.pangolin.routing.acceptor.tun.net.codec;

import io.netty.buffer.ByteBuf;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.UnknownHostException;

/**
 * TCP-over-IPv4 packet buffer.
 *
 * <p>IPv4 header layout (offset from readerIndex):
 * <pre>
 *   Byte  0: Version(4) + IHL(4)
 *   Byte  1: DSCP/ECN (TOS)
 *   Byte  2-3: Total Length
 *   Byte  4-5: Identification
 *   Byte  6-7: Flags + Fragment Offset
 *   Byte  8: TTL
 *   Byte  9: Protocol (= 6 for TCP)
 *   Byte 10-11: Header Checksum
 *   Byte 12-15: Source IP
 *   Byte 16-19: Destination IP
 *   Byte 20+:   TCP header
 * </pre>
 */
public final class Tcp4PacketBuf extends TcpPacketBuf {

    Tcp4PacketBuf(final ByteBuf buf) {
        super(buf);
    }

    @Override
    public int ipHeaderLen() {
        return Ip4Fields.ipHeaderLen(buf, buf.readerIndex());
    }

    @Override
    public byte nextProto() {
        return Ip4Fields.nextProto(buf, buf.readerIndex());
    }

    @Override
    public int hopLimit() {
        return Ip4Fields.hopLimit(buf, buf.readerIndex());
    }

    @Override
    public byte[] srcAddrBytes() {
        return Ip4Fields.srcAddrBytes(buf, buf.readerIndex());
    }

    @Override
    public byte[] dstAddrBytes() {
        return Ip4Fields.dstAddrBytes(buf, buf.readerIndex());
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

    public byte tos() {
        return Ip4Fields.tos(buf, buf.readerIndex());
    }

    public short ipId() {
        return Ip4Fields.ipId(buf, buf.readerIndex());
    }

    public short ipFlags() {
        return Ip4Fields.ipFlags(buf, buf.readerIndex());
    }

    public Inet4Address srcAddr4() {
        return (Inet4Address) srcAddr();
    }

    public Inet4Address dstAddr4() {
        return (Inet4Address) dstAddr();
    }

    @Override
    protected InetAddress toInetAddress(final String host, final byte[] addr) {
        try {
            return host != null
                    ? InetAddress.getByAddress(host, addr)
                    : InetAddress.getByAddress(addr);
        } catch (UnknownHostException e) {
            throw new IllegalStateException("Failed to create Inet4Address", e);
        }
    }
}

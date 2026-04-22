package com.github.pangolin.routing.acceptor.tun.net.codec;

import io.netty.buffer.ByteBuf;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;

/**
 * TCP-over-IPv6 packet buffer.
 *
 * <p>IPv6 fixed header layout (offset from readerIndex, 40 bytes):
 * <pre>
 *   Byte  0-3:  Version(4) + Traffic Class(8) + Flow Label(20)
 *   Byte  4-5:  Payload Length
 *   Byte  6:    Next Header (= 6 for TCP)
 *   Byte  7:    Hop Limit
 *   Byte  8-23: Source Address (16 bytes)
 *   Byte 24-39: Destination Address (16 bytes)
 *   Byte 40+:   TCP header (extension headers not supported)
 * </pre>
 */
public final class Tcp6PacketBuf extends TcpPacketBuf {

    private static final int IP6_HEADER_LEN = 40;

    Tcp6PacketBuf(final ByteBuf buf) {
        super(buf);
    }

    @Override
    public int ipHeaderLen() {
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
        final byte[] addr = new byte[16];
        buf.getBytes(buf.readerIndex() + 8, addr);
        return addr;
    }

    @Override
    public byte[] dstAddrBytes() {
        final byte[] addr = new byte[16];
        buf.getBytes(buf.readerIndex() + 24, addr);
        return addr;
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

    public Inet6Address srcAddr6() {
        return (Inet6Address) srcAddr();
    }

    public Inet6Address dstAddr6() {
        return (Inet6Address) dstAddr();
    }

    @Override
    protected InetAddress toInetAddress(final String host, final byte[] addr) {
        try {
            return host != null
                    ? InetAddress.getByAddress(host, addr)
                    : InetAddress.getByAddress(addr);
        } catch (UnknownHostException e) {
            throw new IllegalStateException("Failed to create Inet6Address", e);
        }
    }
}

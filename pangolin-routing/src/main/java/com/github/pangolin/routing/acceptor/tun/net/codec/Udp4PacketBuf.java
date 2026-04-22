package com.github.pangolin.routing.acceptor.tun.net.codec;

import io.netty.buffer.ByteBuf;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.UnknownHostException;

/**
 * UDP-over-IPv4 packet buffer.
 */
public final class Udp4PacketBuf extends UdpPacketBuf {

    Udp4PacketBuf(final ByteBuf buf) {
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

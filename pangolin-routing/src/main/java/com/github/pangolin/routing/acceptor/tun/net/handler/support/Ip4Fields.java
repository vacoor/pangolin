package com.github.pangolin.routing.acceptor.tun.net.handler.support;

import io.netty.buffer.ByteBuf;

/**
 * Package-private static helpers for reading IPv4 header fields from a raw {@link ByteBuf}.
 * Shared by {@link Ip4PacketBuf}, {@link Tcp4PacketBuf}, and {@link Udp4PacketBuf}
 * to avoid code duplication across the IPv4 class hierarchy.
 */
final class Ip4Fields {

    private Ip4Fields() {
    }

    static int ipHeaderLen(final ByteBuf buf, final int base) {
        return (buf.getByte(base) & 0xF) * 4;
    }

    static byte tos(final ByteBuf buf, final int base) {
        return buf.getByte(base + 1);
    }

    static int totalLength(final ByteBuf buf, final int base) {
        return buf.getUnsignedShort(base + 2);
    }

    static short ipId(final ByteBuf buf, final int base) {
        return buf.getShort(base + 4);
    }

    static short ipFlags(final ByteBuf buf, final int base) {
        return buf.getShort(base + 6);
    }

    static int hopLimit(final ByteBuf buf, final int base) {
        return buf.getUnsignedByte(base + 8);
    }

    static byte nextProto(final ByteBuf buf, final int base) {
        return buf.getByte(base + 9);
    }

    static byte[] srcAddrBytes(final ByteBuf buf, final int base) {
        final byte[] addr = new byte[4];
        buf.getBytes(base + 12, addr);
        return addr;
    }

    static byte[] dstAddrBytes(final ByteBuf buf, final int base) {
        final byte[] addr = new byte[4];
        buf.getBytes(base + 16, addr);
        return addr;
    }
}

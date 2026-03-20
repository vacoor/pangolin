package com.github.pangolin.routing.acceptor.tun.net.handler.support;

import io.netty.buffer.ByteBuf;

/**
 * Abstract base for UDP-over-IP packet wrappers.
 * Extends {@link IpPacketBuf} with UDP header field accessors.
 * Concrete subclasses: {@link Udp4PacketBuf} (IPv4).
 */
public abstract class UdpPacketBuf extends IpPacketBuf {

    protected UdpPacketBuf(final ByteBuf buf) {
        super(buf);
    }

    private int udpBase() {
        return buf.readerIndex() + ipHeaderLen();
    }

    // ---- UDP header field accessors ----

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
}

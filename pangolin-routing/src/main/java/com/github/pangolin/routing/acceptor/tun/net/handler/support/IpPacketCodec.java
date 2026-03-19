package com.github.pangolin.routing.acceptor.tun.net.handler.support;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageCodec;
import org.pcap4j.packet.IllegalRawDataException;
import org.pcap4j.packet.IpPacket;
import org.pcap4j.packet.IpSelector;

import java.util.List;

/**
 *
 */
public class IpPacketCodec extends ByteToMessageCodec<IpPacket> {

    /**
     * Carries the original raw bytes of the IP packet being decoded on this thread.
     * Set in {@link #decode} before firing the packet downstream, allowing
     * {@link com.github.pangolin.routing.acceptor.tun.net.handler.tcp.core.TcpDemultiplexer#consume}
     * to reference the bytes directly (zero-copy) instead of triggering a second
     * pcap4j serialization.  Cleared by
     * {@link IpPacketHandler#channelRead} after the packet is fully processed.
     */
    public static final ThreadLocal<byte[]> RAW_BYTES = new ThreadLocal<>();

    /**
     * {@inheritDoc}
     */
    @Override
    protected void encode(final ChannelHandlerContext ctx,
                          final IpPacket msg, final ByteBuf out) throws Exception {
        out.writeBytes(msg.getRawData());
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void decode(final ChannelHandlerContext ctx,
                          final ByteBuf packet, final List<Object> out) throws Exception {
        final byte[] bytes = new byte[packet.readableBytes()];
        packet.readBytes(bytes);
        RAW_BYTES.set(bytes);
        out.add(parsePacket(bytes));
    }

    private static IpPacket parsePacket(final byte[] bytes) throws IllegalRawDataException {
        return (IpPacket) IpSelector.newPacket(bytes, 0, bytes.length);
    }

}

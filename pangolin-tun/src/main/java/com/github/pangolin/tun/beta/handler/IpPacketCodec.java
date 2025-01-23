package com.github.pangolin.tun.beta.handler;

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
     * {@inheritDoc}
     */
    @Override
    protected void encode(final ChannelHandlerContext ctx, final IpPacket msg, final ByteBuf out) throws Exception {
        out.writeBytes(msg.getRawData());
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void decode(final ChannelHandlerContext ctx, final ByteBuf packet, final List<Object> out) throws Exception {
        byte[] bytes = new byte[packet.readableBytes()];
        packet.readBytes(bytes);
        out.add(parsePacket(bytes));
    }

    private static IpPacket parsePacket(final byte [] bytes) throws IllegalRawDataException {
        return (IpPacket) IpSelector.newPacket(bytes, 0, bytes.length);
    }

}

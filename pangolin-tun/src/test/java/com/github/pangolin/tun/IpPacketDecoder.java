package com.github.pangolin.tun;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageCodec;
import io.netty.handler.codec.MessageToMessageCodec;
import io.netty.handler.codec.MessageToMessageDecoder;
import org.drasyl.channel.tun.TunPacket;
import org.pcap4j.packet.IllegalRawDataException;
import org.pcap4j.packet.IpPacket;
import org.pcap4j.packet.IpSelector;

import java.util.List;

/**
 *
 */
public class IpPacketDecoder extends MessageToMessageCodec<IpPacket, TunPacket> {

    @Override
    protected void encode(final ChannelHandlerContext ctx, final TunPacket msg, final List<Object> out) throws Exception {
        out.add(parsePacket(msg));
    }

    @Override
    protected void decode(final ChannelHandlerContext ctx, final IpPacket msg, final List<Object> out) throws Exception {
        out.add(Unpooled.wrappedBuffer(msg.getRawData()));
    }

    private static IpPacket parsePacket(final TunPacket packet) throws IllegalRawDataException {
        final byte[] bytes = ByteBufUtil.getBytes(packet.content());
        return (IpPacket) IpSelector.newPacket(bytes, 0, bytes.length);
    }

}

package com.github.pangolin.tun;

import io.netty.buffer.ByteBufUtil;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToMessageDecoder;
import org.drasyl.channel.tun.TunPacket;
import org.pcap4j.packet.IllegalRawDataException;
import org.pcap4j.packet.IpPacket;
import org.pcap4j.packet.IpSelector;

import java.util.List;

/**
 *
 */
public class IpPacketDecoder extends MessageToMessageDecoder<TunPacket> {

    @Override
    protected void decode(final ChannelHandlerContext ctx, final TunPacket packet, final List<Object> out) throws Exception {
        out.add(parsePacket(packet));
    }

    private static IpPacket parsePacket(final TunPacket packet) throws IllegalRawDataException {
        final byte[] bytes = ByteBufUtil.getBytes(packet.content());
        return (IpPacket) IpSelector.newPacket(bytes, 0, bytes.length);
    }

}

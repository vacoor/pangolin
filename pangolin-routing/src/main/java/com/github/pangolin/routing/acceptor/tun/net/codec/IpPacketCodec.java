package com.github.pangolin.routing.acceptor.tun.net.codec;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageCodec;

import java.util.List;

/**
 * Codec that wraps raw IP bytes from TUN into {@link IpPacketBuf} (decode)
 * and writes outgoing {@link ByteBuf} directly to the TUN channel (encode).
 */
public class IpPacketCodec extends ByteToMessageCodec<ByteBuf> {

    /**
     * {@inheritDoc}
     *
     * Outgoing direction: the caller writes a raw IP {@link ByteBuf};
     * we forward it directly to the wire.
     */
    @Override
    protected void encode(final ChannelHandlerContext ctx,
                          final ByteBuf msg, final ByteBuf out) throws Exception {
        out.writeBytes(msg);
    }

    /**
     * {@inheritDoc}
     *
     * Incoming direction: wrap the received bytes in an {@link IpPacketBuf}
     * (zero-copy — no field parsing occurs here).
     * {@link IpPacketBuf} retains one reference; the downstream handler is
     * responsible for calling {@link IpPacketBuf#release()} when done.
     */
    @Override
    protected void decode(final ChannelHandlerContext ctx,
                          final ByteBuf packet, final List<Object> out) throws Exception {
        out.add(IpPacketBuf.retainedWrap(packet.readRetainedSlice(packet.readableBytes())));
    }
}

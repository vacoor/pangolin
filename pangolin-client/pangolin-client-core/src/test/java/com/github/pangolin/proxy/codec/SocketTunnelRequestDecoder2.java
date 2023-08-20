package com.github.pangolin.proxy.codec;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.DecoderException;
import io.netty.handler.codec.ReplayingDecoder;

import java.util.List;

public class SocketTunnelRequestDecoder2 extends ReplayingDecoder<SocketTunnelRequestDecoder2.State> {
    private static final byte VERSION_1_0 = 1;

    enum State {
        INIT,
        SUCCESS,
        FAILURE
    }

    @Override
    protected void decode(final ChannelHandlerContext ctx, final ByteBuf in, final List<Object> out) throws Exception {
        final State state = state();
        if (State.INIT.equals(state)) {
            final byte version = in.readByte();
            if (version != VERSION_1_0) {
                throw new DecoderException("unsupported version: " + version + " (expected: " + VERSION_1_0 + ')');
            }
            // out.add(new WebSocketTunnelRequest(version, command, addressType, address, port));
            checkpoint(State.SUCCESS);
        } else if (State.SUCCESS.equals(state)) {
            final int bytes = actualReadableBytes();
            if (bytes > 0) {
                out.add(in.readRetainedSlice(bytes));
            }
        } else if (State.FAILURE.equals(state)) {
            in.skipBytes(actualReadableBytes());
        }
    }

}
package com.github.pangolin.routing.internal.server.ss.codec;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ReplayingDecoder;

import java.util.List;

public class AeadDecoderHandler extends ReplayingDecoder<AeadDecoderHandler.State> {

    public AeadDecoderHandler() {
        super(State.READ_SALT);
    }

    private byte[] salt;
    private int payloadSize;


    @Override
    protected void decode(final ChannelHandlerContext ctx, final ByteBuf in, List<Object> out) throws Exception {
        /*-
         +------+--------------------------+---------------------------+------------------------+-------------------------+-----+
         | salt |  encrypted header chunk  |  encrypted payload chunk  | encrypted header chunk | encrypted payload chunk | ... |
         +------+--------------------------+---------------------------+------------------------+-------------------------+-----+
         |  16B | 2B payload len + 16B tag | variable length + 16B tag | ...                    | ...                     | ... |
         +------+--------------------------+---------------------------+------------------------+-------------------------+-----+
         */
        switch (state()) {
            case READ_SALT:
                ByteBuf byteBuf = in.readBytes(16);
                checkpoint(State.READ_HEADER_CHUNK);
            case READ_HEADER_CHUNK:
                in.readBytes(2 + 16);
                checkpoint(State.READ_PAYLOAD_CHUNK);
            case READ_PAYLOAD_CHUNK:
                ByteBuf payload = in.readBytes(payloadSize);
                checkpoint(State.READ_HEADER_CHUNK);
            default:
        }
    }

    enum State {
        READ_SALT, READ_HEADER_CHUNK, READ_PAYLOAD_CHUNK
    }
}

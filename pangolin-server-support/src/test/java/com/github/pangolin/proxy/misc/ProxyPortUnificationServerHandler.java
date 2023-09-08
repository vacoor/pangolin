package com.github.pangolin.proxy.misc;

import com.github.pangolin.proxy.server.http.HttpProxyServerHandler;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPipeline;
import io.netty.handler.codec.ByteToMessageDecoder;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.socksx.SocksPortUnificationServerHandler;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

/**
 *
 */
@Slf4j
public class ProxyPortUnificationServerHandler extends ByteToMessageDecoder {

    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) throws Exception {
        final int readerIndex = in.readerIndex();
        if (in.writerIndex() == readerIndex || in.readableBytes() < 2) {
            return;
        }

        final int magic1 = in.getUnsignedByte(readerIndex);
        final int magic2 = in.getUnsignedByte(readerIndex + 1);

        final ChannelPipeline p = ctx.pipeline();
        if (isHttp(magic1, magic2)) {
            log.debug("{} Protocol: HTTP", ctx.channel());
            p.addAfter(ctx.name(), null, new HttpObjectAggregator(8 * 1024 * 1024));
            p.addAfter(ctx.name(), null, new HttpServerCodec());
        } else {
            // close on unknown in SocksPortUnificationServerHandler
            p.addAfter(ctx.name(), null, new SocksPortUnificationServerHandler());
        }
        p.remove(this);
    }

    private boolean isHttp(int magic1, int magic2) {
        return magic1 == 'G' && magic2 == 'E' || // GET
                magic1 == 'P' && magic2 == 'O' || // POST
                magic1 == 'P' && magic2 == 'U' || // PUT
                magic1 == 'H' && magic2 == 'E' || // HEAD
                magic1 == 'O' && magic2 == 'P' || // OPTIONS
                magic1 == 'P' && magic2 == 'A' || // PATCH
                magic1 == 'D' && magic2 == 'E' || // DELETE
                magic1 == 'T' && magic2 == 'R' || // TRACE
                magic1 == 'C' && magic2 == 'O';   // CONNECT
    }

}

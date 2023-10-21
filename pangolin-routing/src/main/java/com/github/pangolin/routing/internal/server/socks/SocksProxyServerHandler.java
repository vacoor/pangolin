package com.github.pangolin.routing.internal.server.socks;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelPipeline;
import io.netty.handler.codec.socksx.SocksPortUnificationServerHandler;
import io.netty.handler.codec.socksx.v4.Socks4Message;
import io.netty.handler.codec.socksx.v5.Socks5Message;
import io.netty.util.ReferenceCountUtil;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class SocksProxyServerHandler extends ChannelInboundHandlerAdapter {

    @Override
    public void handlerAdded(final ChannelHandlerContext ctx) throws Exception {
        if (null == ctx.pipeline().get(SocksPortUnificationServerHandler.class)) {
            ctx.pipeline().addBefore(ctx.name(), null, new SocksPortUnificationServerHandler());
        }
    }

    @Override
    public void channelRead(final ChannelHandlerContext ctx, final Object msg) throws Exception {
        final ChannelPipeline cp = ctx.pipeline();
        if (msg instanceof Socks4Message) {
            cp.replace(this, null, new Socks4ProxyServerHandler());
            ctx.fireChannelRead(msg);
        } else if (msg instanceof Socks5Message) {
            cp.replace(this, null, new Socks5ProxyServerHandler());
            ctx.fireChannelRead(msg);
        } else {
            ReferenceCountUtil.release(msg);
            log.warn("{} Unable to receive SOCKS4/5 message: {}", ctx.channel(), msg.getClass().getName());
            ctx.close();
        }
    }

}
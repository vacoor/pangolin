package com.github.pangolin.proxy.bridge.socks5;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.socksx.v5.DefaultSocks5InitialRequest;
import io.netty.handler.codec.socksx.v5.DefaultSocks5InitialResponse;
import io.netty.handler.codec.socksx.v5.Socks5AuthMethod;
import io.netty.handler.codec.socksx.v5.Socks5InitialRequestDecoder;
import io.netty.util.ReferenceCountUtil;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class Socks5InitialHandler extends SimpleChannelInboundHandler<DefaultSocks5InitialRequest> {

    @Override
    public void handlerAdded(final ChannelHandlerContext ctx) throws Exception {
        if (null == ctx.pipeline().get(Socks5InitialRequestDecoder.class)) {
            ctx.pipeline().addBefore(ctx.name(), null, new Socks5InitialRequestDecoder());
        }
    }

    @Override
    public void handlerRemoved(final ChannelHandlerContext ctx) throws Exception {
        ctx.pipeline().remove(Socks5InitialRequestDecoder.class);
    }

    @Override
    protected void channelRead0(final ChannelHandlerContext ctx, final DefaultSocks5InitialRequest msg) throws Exception {
        log.debug("初始化socks5链接");
        boolean failure = msg.decoderResult().isFailure();
        if (failure) {
            log.error("初始化socks5失败，请检查是否是socks5协议");
            ReferenceCountUtil.retain(msg);
            ctx.fireChannelRead(msg);
            return;
        } else {
            ctx.writeAndFlush(new DefaultSocks5InitialResponse(Socks5AuthMethod.NO_AUTH));
        }
        ctx.pipeline().remove(this);
    }
}
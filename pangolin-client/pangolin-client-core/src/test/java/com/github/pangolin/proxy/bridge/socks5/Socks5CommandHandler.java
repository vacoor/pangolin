package com.github.pangolin.proxy.bridge.socks5;

import com.github.pangolin.util.Channels;
import com.github.pangolin.util.Redirects;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.handler.codec.socksx.v5.*;
import io.netty.util.ReferenceCountUtil;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class Socks5CommandHandler extends SimpleChannelInboundHandler<Socks5CommandRequest> {
    private final EventLoopGroup eventLoopGroup;

    public Socks5CommandHandler(final EventLoopGroup eventLoopGroup) {
        this.eventLoopGroup = eventLoopGroup;
    }

    @Override
    public void handlerAdded(final ChannelHandlerContext ctx) throws Exception {
        if (null == ctx.pipeline().get(Socks5CommandRequestDecoder.class)) {
            ctx.pipeline().addBefore(ctx.name(), null, new Socks5CommandRequestDecoder());
        }
    }

    @Override
    public void handlerRemoved(final ChannelHandlerContext ctx) throws Exception {
        ctx.pipeline().remove(Socks5CommandRequestDecoder.class);
    }


    @Override
    protected void channelRead0(final ChannelHandlerContext requestCtx, final Socks5CommandRequest request) throws Exception {
        final Socks5CommandType type = request.type();
        if (Socks5CommandType.CONNECT.equals(type)) {
            final int port = request.dstPort();
            final String addr = request.dstAddr();
            final Socks5AddressType addrType = request.dstAddrType();

            log.debug("连接目标地址({}/{}:{})...", addrType, addr, port);

            requestCtx.channel().config().setAutoRead(false);
            Channels.open(addr, port, false, eventLoopGroup, new ChannelInboundHandlerAdapter() {

                @Override
                public void channelRegistered(final ChannelHandlerContext targetCtx) throws Exception {
                    targetCtx.pipeline().replace(targetCtx.handler(), null, Redirects.socketRedirectToSocket(requestCtx));
                    requestCtx.pipeline().replace(requestCtx.handler(), null, Redirects.socketRedirectToSocket(targetCtx));

                    targetCtx.channel().config().setAutoRead(true);
                    requestCtx.channel().config().setAutoRead(true);
                }

            }).addListener(f -> {
                if (f.isSuccess()) {
                    log.debug("连接到目标地址({}/{}:{})成功", addrType, addr, port);
                    requestCtx.writeAndFlush(new DefaultSocks5CommandResponse(Socks5CommandStatus.SUCCESS, addrType));
                } else {
                    log.warn("连接到目标地址({}/{}:{})失败: {}", addrType, addr, port, f.cause());
                    requestCtx.writeAndFlush(new DefaultSocks5CommandResponse(Socks5CommandStatus.FAILURE, addrType));
                }
            }).sync().channel().closeFuture().addListener(f -> {
                if (requestCtx.channel().isActive()) {
                    log.info("目标地址({}/{}:{})断开连接", addrType, addr, port, f.cause());
                    requestCtx.writeAndFlush(Unpooled.EMPTY_BUFFER).addListener(ChannelFutureListener.CLOSE);
                }
            });
        } else {
            ReferenceCountUtil.retain(request);
            requestCtx.fireChannelRead(request);
        }

    }

}
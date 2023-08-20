package com.github.pangolin.proxy.bridge.socks5;

import com.github.pangolin.util.Channels;
import com.github.pangolin.util.Redirects;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.handler.codec.socksx.v5.*;
import io.netty.util.ReferenceCountUtil;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class Socks5CommandHandler2 extends SimpleChannelInboundHandler<Socks5CommandRequest> {
    private final EventLoopGroup eventLoopGroup;

    public Socks5CommandHandler2(final EventLoopGroup eventLoopGroup) {
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
//            int port = request.dstPort();
//            String addr = request.dstAddr();
            final String addr = "127.0.0.1";
            final int port = 8888;
            final Socks5AddressType addrType = request.dstAddrType();

            log.debug("连接目标地址({}/{}:{})...", addrType, addr, port);

            requestCtx.channel().config().setAutoRead(false);

            Channels.open(addr, port, eventLoopGroup, new ChannelInboundHandlerAdapter() {

                @Override
                public void channelRegistered(final ChannelHandlerContext targetCtx) throws Exception {
                    log.info("Registered");
                    targetCtx.channel().config().setAutoRead(false);

                    targetCtx.pipeline().remove(targetCtx.handler());
                    requestCtx.pipeline().remove(requestCtx.handler());

                    targetCtx.pipeline().addLast(Redirects.socketRedirectToSocket(requestCtx));
                    requestCtx.pipeline().addLast(Redirects.socketRedirectToSocket(targetCtx));

                    targetCtx.channel().config().setAutoRead(true);
                    requestCtx.channel().config().setAutoRead(true);


//                    targetCtx.writeAndFlush(out);
//                    targetCtx.writeAndFlush(out).addListener(c -> {

//                    });


//                        log.debug("连接到目标地址({}/{}:{})成功", addrType, addr, port);
//                        requestCtx.writeAndFlush(new DefaultSocks5CommandResponse(Socks5CommandStatus.SUCCESS, addrType));


//                    log.debug("连接到目标地址({}/{}:{})成功", addrType, addr, port);
//                    requestCtx.writeAndFlush(new DefaultSocks5CommandResponse(Socks5CommandStatus.SUCCESS, addrType));
                }

            }).addListener(f -> {
                if (f.isSuccess()) {
                    log.warn("连接到目标地址({}/{}:{})成功", addrType, addr, port);
                    final ByteBuf out = Unpooled.buffer();
                    out.writeByte(request.version().byteValue());
                    out.writeByte(request.type().byteValue());
                    out.writeByte(0x00);

                    final Socks5AddressType dstAddrType = request.dstAddrType();
                    out.writeByte(dstAddrType.byteValue());
                    Socks5AddressEncoder addressEncoder = Socks5AddressEncoder.DEFAULT;
                    addressEncoder.encodeAddress(dstAddrType, request.dstAddr(), out);
                    out.writeShort(request.dstPort());
//                    targetCtx.writeAndFlush(out);
                    ((ChannelFuture)f).channel().writeAndFlush(out).addListener(c -> {
                    requestCtx.writeAndFlush(new DefaultSocks5CommandResponse(Socks5CommandStatus.SUCCESS, addrType));
                    });
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
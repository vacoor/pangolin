package com.github.pangolin.proxy.server.socks.v4;

import com.github.pangolin.handler.SocketInboundRedirectHandler;
import com.github.pangolin.util.Channels;
import io.netty.channel.*;
import io.netty.handler.codec.socksx.v4.*;
import io.netty.util.ReferenceCountUtil;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class Socks4ProxyServerHandler extends ChannelInboundHandlerAdapter {
    private static final String NONE = "";

    private final String uid;
    private final EventLoopGroup proxyGroup;

    public Socks4ProxyServerHandler(final EventLoopGroup proxyGroup) {
        this(NONE, proxyGroup);
    }

    public Socks4ProxyServerHandler(final String uid, final EventLoopGroup proxyGroup) {
        this.uid = null != uid ? uid : NONE;
        this.proxyGroup = proxyGroup;
    }

    @Override
    public void handlerAdded(final ChannelHandlerContext ctx) throws Exception {
        final ChannelPipeline cp = ctx.pipeline();
        if (null == cp.get(Socks4ServerDecoder.class)) {
            cp.addBefore(ctx.name(), null, new Socks4ServerDecoder());
        }
        if (null == cp.get(Socks4ServerEncoder.class)) {
            cp.addBefore(ctx.name(), null, Socks4ServerEncoder.INSTANCE);
        }
    }

    @Override
    public void handlerRemoved(final ChannelHandlerContext ctx) throws Exception {
        final ChannelPipeline cp = ctx.pipeline();
        if (null != cp.get(Socks4ServerDecoder.class)) {
            cp.remove(Socks4ServerDecoder.class);
        }
        /*
        if (null != cp.get(Socks4ServerEncoder.class)) {
            cp.remove(Socks4ServerEncoder.class);
        }
        */
    }

    @Override
    public void channelRead(final ChannelHandlerContext ctx, final Object msg) throws Exception {
        try {
            if (msg instanceof Socks4Message && ((Socks4Message) msg).decoderResult().isSuccess()) {
                if (msg instanceof Socks4CommandRequest) {
                    final Socks4CommandRequest request = (Socks4CommandRequest) msg;
                    final String requestUid = request.userId();
                    final Socks4CommandType type = request.type();

                    if (!nullSafeEquals(uid, requestUid)) {
                        ctx.writeAndFlush(new DefaultSocks4CommandResponse(Socks4CommandStatus.IDENTD_AUTH_FAILURE)).addListener(ChannelFutureListener.CLOSE);
                    } else if (!Socks4CommandType.CONNECT.equals(type)) {
                        ctx.writeAndFlush(new DefaultSocks4CommandResponse(Socks4CommandStatus.REJECTED_OR_FAILED)).addListener(ChannelFutureListener.CLOSE);
                    } else {
                        connect(ctx, request, proxyGroup);
                    }
                } else {
                    Channels.closeOnFlush(ctx.channel());
                    log.error("Connection closed by Malformed Packet: {}", msg);
                }
            } else {
                Channels.closeOnFlush(ctx.channel());
                log.error("Connection closed by Malformed Packet: {}", msg);
            }
        } finally {
            ReferenceCountUtil.release(msg);
        }
    }

    protected void connect(final ChannelHandlerContext ctx, final Socks4CommandRequest request, final EventLoopGroup proxyGroup) throws Exception {
        final int port = request.dstPort();
        final String address = request.dstAddr();

        ctx.channel().config().setAutoRead(false);
        Channels.open(address, port, false, proxyGroup, new ChannelInboundHandlerAdapter() {
            @Override
            public void channelRegistered(final ChannelHandlerContext delegateCtx) throws Exception {
                delegateCtx.pipeline().replace(this, null, new SocketInboundRedirectHandler(ctx));
                ctx.pipeline().replace(ctx.handler(), null, new SocketInboundRedirectHandler(delegateCtx));

                delegateCtx.channel().config().setAutoRead(true);
                ctx.channel().config().setAutoRead(true);
            }
        }).addListener(future -> {
            if (future.isSuccess()) {
                log.info("Connection to {}:{}: Connected", address, port);
                ctx.writeAndFlush(new DefaultSocks4CommandResponse(Socks4CommandStatus.SUCCESS)).addListener(g -> ctx.pipeline().remove(Socks4ServerEncoder.INSTANCE));
            } else {
                log.warn("Failed to Connect to {}:{}: {}", address, port, future.cause());
                ctx.writeAndFlush(new DefaultSocks4CommandResponse(Socks4CommandStatus.IDENTD_UNREACHABLE)).addListener(ChannelFutureListener.CLOSE);
            }
        }).channel().closeFuture().addListener(future -> {
            if (ctx.channel().isActive()) {
                log.info("Connection to {}:{} closed", address, port);
                Channels.closeOnFlush(ctx.channel());
            }
        });
    }

    private static boolean nullSafeEquals(Object a, Object b) {
        return (a == b) || (a != null && a.equals(b));
    }
}

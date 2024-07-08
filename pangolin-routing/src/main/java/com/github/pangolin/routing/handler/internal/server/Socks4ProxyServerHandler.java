package com.github.pangolin.routing.handler.internal.server;

import com.github.pangolin.handler.TcpInboundRedirectHandler;
import com.github.pangolin.routing.handler.internal.server.support.SocketChannelFactory;
import com.github.pangolin.routing.handler.internal.server.support.StandardSocketChannelFactory;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.handler.codec.socksx.v4.*;
import io.netty.util.ReferenceCountUtil;
import lombok.extern.slf4j.Slf4j;

import java.net.InetSocketAddress;
import java.net.SocketAddress;

@Slf4j
public class Socks4ProxyServerHandler extends ChannelInboundHandlerAdapter {
    private static final String NONE = "";

    private final String uid;
    private final SocketChannelFactory factory;

    public Socks4ProxyServerHandler() {
        this(NONE);
    }

    public Socks4ProxyServerHandler(final String uid) {
        this(uid, new StandardSocketChannelFactory());
    }

    public Socks4ProxyServerHandler(final String uid, final SocketChannelFactory factory) {
        this.uid = null != uid ? uid : NONE;
        this.factory = factory;
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
    }

    @Override
    public void channelRead(final ChannelHandlerContext ctx, final Object msg) throws Exception {
        try {
            if (!(msg instanceof Socks4Message) || !((Socks4Message) msg).decoderResult().isSuccess()) {
                log.warn("[SOCKS4a] Connection closed by UNKNOWN message '{}'", msg.getClass().getName());
                ctx.channel().writeAndFlush(Unpooled.EMPTY_BUFFER).addListener(ChannelFutureListener.CLOSE);
                return;
            }

            final SocketAddress clientAddress = ctx.channel().remoteAddress();
            if (msg instanceof Socks4CommandRequest) {
                final Socks4CommandRequest request = (Socks4CommandRequest) msg;
                final String requestUid = request.userId();
                final Socks4CommandType type = request.type();

                final int port = request.dstPort();
                final String address = request.dstAddr();

                log.info("[SOCKS4a] Received {} {} request => {}:{}", clientAddress, type.toString(), address, port);

                if (!nullSafeEquals(uid, requestUid)) {
                    log.warn("[SOCKS4a] Respond not permitted to {}", clientAddress);
                    ctx.writeAndFlush(new DefaultSocks4CommandResponse(Socks4CommandStatus.IDENTD_AUTH_FAILURE)).addListener(ChannelFutureListener.CLOSE);
                } else if (Socks4CommandType.CONNECT.equals(type)) {
                    this.connect(ctx, request).addListener(new ChannelFutureListener() {
                        @Override
                        public void operationComplete(final ChannelFuture future) throws Exception {
                            if (future.isSuccess()) {
                                log.debug("[SOCKS4a] Connection established => {}:{}", address, port);
                                ctx.writeAndFlush(new DefaultSocks4CommandResponse(Socks4CommandStatus.SUCCESS)).addListener(removeOnComplete(ctx, Socks4ServerEncoder.INSTANCE));
                            } else {
                                log.error("[SOCKS4a] Error: {}/{} => {}:{}", future.cause().getMessage(), future.cause().getClass().getSimpleName(), address, port);
                                ctx.writeAndFlush(new DefaultSocks4CommandResponse(Socks4CommandStatus.IDENTD_UNREACHABLE)).addListener(ChannelFutureListener.CLOSE);
                            }
                        }
                    }).channel().closeFuture().addListener(new ChannelFutureListener() {
                        @Override
                        public void operationComplete(final ChannelFuture future) throws Exception {
                            if (ctx.channel().isActive()) {
                                ctx.channel().writeAndFlush(Unpooled.EMPTY_BUFFER).addListener(ChannelFutureListener.CLOSE);
                            }
                            log.info("[SOCKS4a] Connection closed => {}:{}", address, port);
                        }
                    });
                } else {
                    ctx.writeAndFlush(new DefaultSocks4CommandResponse(Socks4CommandStatus.REJECTED_OR_FAILED)).addListener(ChannelFutureListener.CLOSE);
                }
            } else {
                log.error("[SOCKS4a] Connection closed by UNKNOWN message '{}'", msg.getClass().getName());
                ctx.channel().writeAndFlush(Unpooled.EMPTY_BUFFER).addListener(ChannelFutureListener.CLOSE);
            }
        } finally {
            ReferenceCountUtil.release(msg);
        }
    }

    private ChannelFutureListener removeOnComplete(final ChannelHandlerContext ctx, final ChannelHandler h) {
        return new ChannelFutureListener() {
            @Override
            public void operationComplete(final ChannelFuture channelFuture) throws Exception {
                ctx.pipeline().remove(h);
            }
        };
    }

    @Override
    public void exceptionCaught(final ChannelHandlerContext ctx, final Throwable cause) throws Exception {
        log.error("Software caused connection abort: {}", cause.getMessage(), cause);
        ctx.channel().writeAndFlush(Unpooled.EMPTY_BUFFER).addListener(ChannelFutureListener.CLOSE);
    }

    protected ChannelFuture connect(final ChannelHandlerContext ctx, final Socks4CommandRequest request) throws Exception {
        final int port = request.dstPort();
        final String address = request.dstAddr();

        ctx.channel().config().setAutoRead(false);

        // FIXME
        final InetSocketAddress addr = InetSocketAddress.createUnresolved(address, port);
        return factory.open(addr, ctx.channel().config().getConnectTimeoutMillis(), false, ctx.channel().eventLoop(), new ChannelInboundHandlerAdapter() {
            @Override
            public void channelRegistered(final ChannelHandlerContext delegateCtx) throws Exception {
                delegateCtx.pipeline().replace(this, null, new TcpInboundRedirectHandler(ctx));
                ctx.pipeline().replace(ctx.handler(), null, new TcpInboundRedirectHandler(delegateCtx));

                delegateCtx.channel().config().setAutoRead(true);
                ctx.channel().config().setAutoRead(true);
            }
        });
    }

    private static boolean nullSafeEquals(Object a, Object b) {
        return (a == b) || (a != null && a.equals(b));
    }
}

package com.github.pangolin.routing.handler.internal.server;

import com.github.pangolin.handler.TcpInboundRedirectHandler;
import com.github.pangolin.routing.handler.internal.server.support.ChannelFactory;
import com.github.pangolin.routing.handler.internal.server.support.StandardChannelFactory;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelPipeline;
import io.netty.handler.codec.socksx.v4.DefaultSocks4CommandResponse;
import io.netty.handler.codec.socksx.v4.Socks4CommandRequest;
import io.netty.handler.codec.socksx.v4.Socks4CommandStatus;
import io.netty.handler.codec.socksx.v4.Socks4CommandType;
import io.netty.handler.codec.socksx.v4.Socks4Message;
import io.netty.handler.codec.socksx.v4.Socks4ServerDecoder;
import io.netty.handler.codec.socksx.v4.Socks4ServerEncoder;
import io.netty.util.ReferenceCountUtil;
import lombok.extern.slf4j.Slf4j;

import java.net.InetSocketAddress;

@Slf4j
public class Socks4ProxyServerHandler extends ChannelInboundHandlerAdapter {
    private static final String NONE = "";

    private final String username;
    private final ChannelFactory factory;

    public Socks4ProxyServerHandler() {
        this(NONE);
    }

    public Socks4ProxyServerHandler(final String username) {
      this(username, new StandardChannelFactory());
    }

    public Socks4ProxyServerHandler(final String username, final ChannelFactory factory) {
        this.username = null != username ? username : NONE;
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
                log.error("Connection closed by UNKNOWN message: {}", msg.getClass().getName());
                ctx.channel().writeAndFlush(Unpooled.EMPTY_BUFFER).addListener(ChannelFutureListener.CLOSE);
                return;
            }

            if (msg instanceof Socks4CommandRequest) {
                final Socks4CommandRequest request = (Socks4CommandRequest) msg;
                final String requestUid = request.userId();
                final Socks4CommandType type = request.type();

                if (!nullSafeEquals(username, requestUid)) {
                    ctx.writeAndFlush(new DefaultSocks4CommandResponse(Socks4CommandStatus.IDENTD_AUTH_FAILURE)).addListener(ChannelFutureListener.CLOSE);
                } else if (!Socks4CommandType.CONNECT.equals(type)) {
                    ctx.writeAndFlush(new DefaultSocks4CommandResponse(Socks4CommandStatus.REJECTED_OR_FAILED)).addListener(ChannelFutureListener.CLOSE);
                } else {
                    connect(ctx, request).addListener(new ChannelFutureListener() {
                        @Override
                        public void operationComplete(final ChannelFuture future) throws Exception {
                            if (future.isSuccess()) {
                                log.info("Connection established: {}", future.channel().remoteAddress());
                                ctx.writeAndFlush(new DefaultSocks4CommandResponse(Socks4CommandStatus.SUCCESS)).addListener(removeOnComplete(ctx, Socks4ServerEncoder.INSTANCE));
                            } else {
                                log.info("Failed to Connect to {}: {}", future.channel().remoteAddress(), future.cause().getMessage(), future.cause());
                                ctx.writeAndFlush(new DefaultSocks4CommandResponse(Socks4CommandStatus.IDENTD_UNREACHABLE)).addListener(ChannelFutureListener.CLOSE);
                            }
                        }
                    }).channel().closeFuture().addListener(new ChannelFutureListener() {
                        @Override
                        public void operationComplete(final ChannelFuture future) throws Exception {
                            log.info("Connection to {} closed", future.channel().remoteAddress());
                            if (ctx.channel().isActive()) {
                                ctx.channel().writeAndFlush(Unpooled.EMPTY_BUFFER).addListener(ChannelFutureListener.CLOSE);
                            }
                        }
                    });
                }
            } else {
                log.error("Connection closed by UNKNOWN message: {}", msg.getClass().getName());
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
        return factory.open(new InetSocketAddress(address, port), ctx.channel().config().getConnectTimeoutMillis(), false, ctx.channel().eventLoop(), new ChannelInboundHandlerAdapter() {
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

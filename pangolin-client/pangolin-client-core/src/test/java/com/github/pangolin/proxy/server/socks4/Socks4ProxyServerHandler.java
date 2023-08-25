package com.github.pangolin.proxy.server.socks4;

import com.github.pangolin.util.Channels;
import com.github.pangolin.util.SocketInboundRedirectHandler;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.handler.codec.socksx.v4.DefaultSocks4CommandResponse;
import io.netty.handler.codec.socksx.v4.Socks4CommandRequest;
import io.netty.handler.codec.socksx.v4.Socks4CommandStatus;
import io.netty.handler.codec.socksx.v4.Socks4CommandType;
import io.netty.handler.codec.socksx.v4.Socks4Message;
import io.netty.handler.codec.socksx.v4.Socks4ServerDecoder;
import io.netty.handler.codec.socksx.v4.Socks4ServerEncoder;
import lombok.extern.slf4j.Slf4j;

/**
 * SOCKS4.
 *
 * @author changhe.yang
 * @since 20230821
 */
@Slf4j
public class Socks4ProxyServerHandler extends ChannelInboundHandlerAdapter {
    private static final String NONE = "";

    private final String uid;
    private final NioEventLoopGroup proxyWorkersGroup;

    public Socks4ProxyServerHandler(final NioEventLoopGroup proxyWorkersGroup) {
        this(NONE, proxyWorkersGroup);
    }

    public Socks4ProxyServerHandler(final String uid, final NioEventLoopGroup proxyWorkersGroup) {
        this.uid = null != uid ? uid : NONE;
        this.proxyWorkersGroup = proxyWorkersGroup;
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
        if (msg instanceof Socks4Message) {
            final Socks4Message socks4Msg = (Socks4Message) msg;
            if (!socks4Msg.decoderResult().isSuccess()) {
                log.error("not support message: {}", msg);
                ctx.writeAndFlush(Unpooled.EMPTY_BUFFER).addListener(ChannelFutureListener.CLOSE);
                return;
            }

            if (msg instanceof Socks4CommandRequest) {
                final Socks4CommandRequest request = (Socks4CommandRequest) msg;
                final Socks4CommandType type = request.type();
                final String requestUid = request.userId();
                if (nullSafeEquals(uid, requestUid) && Socks4CommandType.CONNECT.equals(type)) {
                    connectToTarget(proxyWorkersGroup, ctx, request);
                } else {
                    ctx.writeAndFlush(new DefaultSocks4CommandResponse(Socks4CommandStatus.REJECTED_OR_FAILED)).addListener(ChannelFutureListener.CLOSE);
                }
            } else {
                log.error("illegal message: {}", msg);
                ctx.writeAndFlush(new DefaultSocks4CommandResponse(Socks4CommandStatus.REJECTED_OR_FAILED)).addListener(ChannelFutureListener.CLOSE);
            }
        } else {
            log.error("not support message: {}", msg);
            ctx.writeAndFlush(Unpooled.EMPTY_BUFFER).addListener(ChannelFutureListener.CLOSE);
        }
    }

    protected void connectToTarget(final NioEventLoopGroup group, final ChannelHandlerContext requestCtx, Socks4CommandRequest request) throws InterruptedException {
        final int port = request.dstPort();
        final String address = request.dstAddr();

        /*-
         *
         */
        requestCtx.channel().config().setAutoRead(false);
        Channels.open(address, port, false, group, new ChannelInboundHandlerAdapter() {
            @Override
            public void channelRegistered(final ChannelHandlerContext delegateCtx) throws Exception {
                delegateCtx.pipeline().replace(this, null, new SocketInboundRedirectHandler(requestCtx));
                requestCtx.pipeline().replace(requestCtx.handler(), null, new SocketInboundRedirectHandler(delegateCtx));

                delegateCtx.channel().config().setAutoRead(true);
                requestCtx.channel().config().setAutoRead(true);
            }
        }).addListener(f -> {
            if (f.isSuccess()) {
                log.debug("连接到目标地址({}:{})成功", address, port);
                requestCtx.writeAndFlush(new DefaultSocks4CommandResponse(Socks4CommandStatus.SUCCESS)).addListener(g -> requestCtx.pipeline().remove(Socks4ServerEncoder.INSTANCE));
            } else {
                log.warn("连接到目标地址({}:{})失败: {}", address, port, f.cause());
                requestCtx.writeAndFlush(new DefaultSocks4CommandResponse(Socks4CommandStatus.IDENTD_UNREACHABLE)).addListener(ChannelFutureListener.CLOSE);
            }
        }).channel().closeFuture().addListener(f -> {
            if (requestCtx.channel().isActive()) {
                log.info("目标地址({}:{})断开连接", address, port, f.cause());
                requestCtx.writeAndFlush(Unpooled.EMPTY_BUFFER).addListener(ChannelFutureListener.CLOSE);
            }
        });
    }

    private static boolean nullSafeEquals(Object a, Object b) {
        return (a == b) || (a != null && a.equals(b));
    }
}

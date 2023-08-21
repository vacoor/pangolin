package com.github.pangolin.proxy.bridge.socks5;

import com.github.pangolin.util.Channels;
import com.github.pangolin.util.Redirects;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.handler.codec.socksx.v5.*;
import lombok.extern.slf4j.Slf4j;

/**
 * TODO DOC ME!.
 *
 * @author changhe.yang
 * @since 20230821
 */
@Slf4j
public class Socks5ProxyServerHandler extends ChannelInboundHandlerAdapter {
    private final NioEventLoopGroup group;
    private final String username;
    private final String password;

    public Socks5ProxyServerHandler(final NioEventLoopGroup group) {
        this(group, null, null);
    }

    public Socks5ProxyServerHandler(final NioEventLoopGroup group, final String username, final String password) {
        this.group = group;
        this.username = username;
        this.password = password;
    }

    @Override
    public void handlerAdded(final ChannelHandlerContext ctx) throws Exception {
        final ChannelPipeline cp = ctx.pipeline();
        cp.addBefore(ctx.name(), null, new Socks5InitialRequestDecoder());
        if (isHasAuthorization()) {
            cp.addBefore(ctx.name(), null, new Socks5PasswordAuthRequestDecoder());
        } else {
            cp.addBefore(ctx.name(), null, new Socks5CommandRequestDecoder());
        }
        cp.addBefore(ctx.name(), null, Socks5ServerEncoder.DEFAULT);
    }

    @Override
    public void handlerRemoved(final ChannelHandlerContext ctx) throws Exception {
        final ChannelPipeline cp = ctx.pipeline();

        cp.remove(Socks5InitialRequestDecoder.class);
        if (null != cp.get(Socks5PasswordAuthRequestDecoder.class)) {
            cp.remove(Socks5PasswordAuthRequestDecoder.class);
        }
        if (null != cp.get(Socks5CommandRequestDecoder.class)) {
            cp.remove(Socks5CommandRequestDecoder.class);
        }
    }

    private boolean isHasAuthorization() {
        return null != username && !username.isEmpty();
    }

    @Override
    public void channelRead(final ChannelHandlerContext ctx, final Object msg) throws Exception {
        if (msg instanceof Socks5InitialRequest) {
            ctx.writeAndFlush(new DefaultSocks5InitialResponse(isHasAuthorization() ? Socks5AuthMethod.PASSWORD : Socks5AuthMethod.NO_AUTH));
        } else if (msg instanceof Socks5PasswordAuthRequest) {
            final Socks5PasswordAuthRequest request = (Socks5PasswordAuthRequest) msg;
            if (nullSafeEquals(username, request.username()) && nullSafeEquals(password, request.password())) {
                final ChannelHandlerContext context = ctx.pipeline().context(Socks5PasswordAuthRequestDecoder.class);
                ctx.pipeline().addAfter(context.name(), null, new Socks5CommandRequestDecoder());
                ctx.writeAndFlush(new DefaultSocks5PasswordAuthResponse(Socks5PasswordAuthStatus.SUCCESS));
            } else {
                ctx.writeAndFlush(new DefaultSocks5PasswordAuthResponse(Socks5PasswordAuthStatus.FAILURE));
            }
        } else if (msg instanceof Socks5CommandRequest) {
            final Socks5CommandRequest request = (Socks5CommandRequest) msg;
            final Socks5CommandType type = request.type();
            final Socks5AddressType addressType = request.dstAddrType();
            if (Socks5CommandType.CONNECT.equals(type)) {
                connectToTarget(group, ctx, request);
            } else {
                ctx.writeAndFlush(new DefaultSocks5CommandResponse(Socks5CommandStatus.COMMAND_UNSUPPORTED, addressType));
            }
        } else {
            // ..
            ctx.writeAndFlush(Unpooled.EMPTY_BUFFER).addListener(ChannelFutureListener.CLOSE);
        }
    }

    protected void connectToTarget(final NioEventLoopGroup group, final ChannelHandlerContext requestCtx, Socks5CommandRequest request) throws InterruptedException {
        final int port = request.dstPort();
        final String address = request.dstAddr();
        final Socks5AddressType addressType = request.dstAddrType();

        requestCtx.channel().config().setAutoRead(false);
        Channels.open(address, port, false, group, new ChannelInboundHandlerAdapter() {
            @Override
            public void channelRegistered(final ChannelHandlerContext delegateCtx) throws Exception {
                requestCtx.pipeline().replace(requestCtx.handler(), null, Redirects.socketRedirectToSocket(delegateCtx));
                delegateCtx.pipeline().replace(this, null, Redirects.socketRedirectToSocket(requestCtx));

                requestCtx.channel().config().setAutoRead(true);
                delegateCtx.channel().config().setAutoRead(true);
            }
        }).addListener(f -> {
            if (f.isSuccess()) {
                log.debug("连接到目标地址({}/{}:{})成功", addressType, address, port);
                requestCtx.writeAndFlush(new DefaultSocks5CommandResponse(Socks5CommandStatus.SUCCESS, addressType)).addListener(g -> requestCtx.pipeline().remove(Socks5ServerEncoder.DEFAULT));
            } else {
                log.warn("连接到目标地址({}/{}:{})失败: {}", addressType, address, port, f.cause());
                requestCtx.writeAndFlush(new DefaultSocks5CommandResponse(Socks5CommandStatus.HOST_UNREACHABLE, addressType));
            }
        }).channel().closeFuture().addListener(f -> {
            if (requestCtx.channel().isActive()) {
                log.info("目标地址({}/{}:{})断开连接", addressType, address, port, f.cause());
                requestCtx.writeAndFlush(Unpooled.EMPTY_BUFFER).addListener(ChannelFutureListener.CLOSE);
            }
        });
    }

    private static boolean nullSafeEquals(Object a, Object b) {
        return (a == b) || (a != null && a.equals(b));
    }
}

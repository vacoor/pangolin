package com.github.pangolin.server;

import com.github.pangolin.handler.SocketInboundRedirectHandler;
import com.github.pangolin.util.Channels;
import io.netty.channel.*;
import io.netty.handler.codec.ByteToMessageDecoder;
import io.netty.handler.codec.socksx.v5.*;
import io.netty.util.ReferenceCountUtil;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class Socks5ProxyServerHandler extends ChannelInboundHandlerAdapter {
    private static final String DEFAULT_DECODER_NAME = "Socks5ServerDecoder";

    private String decoderName = DEFAULT_DECODER_NAME;
    private final EventLoopGroup proxyGroup;
    private final String username;
    private final String password;

    public Socks5ProxyServerHandler(final EventLoopGroup proxyGroup) {
        this(null, null, proxyGroup);
    }

    public Socks5ProxyServerHandler(final String username, final String password, final EventLoopGroup proxyGroup) {
        this.username = username;
        this.password = password;
        this.proxyGroup = proxyGroup;
    }

    @Override
    public void handlerAdded(final ChannelHandlerContext ctx) throws Exception {
        final ChannelPipeline cp = ctx.pipeline();
        final Socks5InitialRequestDecoder decoder = cp.get(Socks5InitialRequestDecoder.class);
        if (null == decoder) {
            cp.addBefore(ctx.name(), decoderName, new Socks5InitialRequestDecoder());
        } else {
            decoderName = cp.context(decoder).name();
        }
        /*
        if (isHasAuthorization()) {
            cp.addBefore(ctx.name(), null, new Socks5PasswordAuthRequestDecoder());
        } else {
            cp.addBefore(ctx.name(), null, new Socks5CommandRequestDecoder());
        }
        */
        if (null == cp.get(Socks5ServerEncoder.class)) {
            cp.addBefore(ctx.name(), null, Socks5ServerEncoder.DEFAULT);
        }
    }

    @Override
    public void handlerRemoved(final ChannelHandlerContext ctx) throws Exception {
        final ChannelPipeline cp = ctx.pipeline();
        if (null != cp.get(Socks5InitialRequestDecoder.class)) {
            cp.remove(Socks5InitialRequestDecoder.class);
        }
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
        try {
            if (msg instanceof Socks5Message && ((Socks5Message) msg).decoderResult().isSuccess()) {
                if (msg instanceof Socks5InitialRequest) {
                    final boolean needAuth = isHasAuthorization();
                    final ByteToMessageDecoder newDecoder = needAuth ? new Socks5PasswordAuthRequestDecoder() : new Socks5CommandRequestDecoder();
                    ctx.pipeline().replace(decoderName, decoderName, newDecoder);
                    ctx.writeAndFlush(new DefaultSocks5InitialResponse(isHasAuthorization() ? Socks5AuthMethod.PASSWORD : Socks5AuthMethod.NO_AUTH));
                } else if (msg instanceof Socks5PasswordAuthRequest) {
                    final Socks5PasswordAuthRequest request = (Socks5PasswordAuthRequest) msg;
                    if (nullSafeEquals(username, request.username()) && nullSafeEquals(password, request.password())) {
                        ctx.pipeline().replace(decoderName, decoderName, new Socks5CommandRequestDecoder());
                        ctx.writeAndFlush(new DefaultSocks5PasswordAuthResponse(Socks5PasswordAuthStatus.SUCCESS));
                    } else {
                        ctx.writeAndFlush(new DefaultSocks5PasswordAuthResponse(Socks5PasswordAuthStatus.FAILURE));
                    }
                } else if (msg instanceof Socks5CommandRequest) {
                    final Socks5CommandRequest request = (Socks5CommandRequest) msg;
                    final Socks5CommandType type = request.type();
                    final Socks5AddressType addressType = request.dstAddrType();
                    if (!Socks5CommandType.CONNECT.equals(type)) {
                        ctx.writeAndFlush(new DefaultSocks5CommandResponse(Socks5CommandStatus.COMMAND_UNSUPPORTED, addressType)).addListener(ChannelFutureListener.CLOSE);
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

    protected void connect(final ChannelHandlerContext requestCtx, final Socks5CommandRequest request, final EventLoopGroup proxyGroup) throws Exception {
        final int port = request.dstPort();
        final String address = request.dstAddr();
        final Socks5AddressType addressType = request.dstAddrType();

        requestCtx.channel().config().setAutoRead(false);
        Channels.open(address, port, false, proxyGroup, new ChannelInboundHandlerAdapter() {
            @Override
            public void channelRegistered(final ChannelHandlerContext delegateCtx) throws Exception {
                delegateCtx.pipeline().replace(this, null, new SocketInboundRedirectHandler(requestCtx));
                requestCtx.pipeline().replace(requestCtx.handler(), null, new SocketInboundRedirectHandler(delegateCtx));

                delegateCtx.channel().config().setAutoRead(true);
                requestCtx.channel().config().setAutoRead(true);
            }
        }).addListener(future -> {
            if (future.isSuccess()) {
                log.info("Connection to {}:{}: Connected", address, port);
                requestCtx.writeAndFlush(new DefaultSocks5CommandResponse(Socks5CommandStatus.SUCCESS, addressType)).addListener(g -> requestCtx.pipeline().remove(Socks5ServerEncoder.DEFAULT));
            } else {
                log.warn("Failed to Connect to {}:{}: {}", address, port, future.cause());
                requestCtx.writeAndFlush(new DefaultSocks5CommandResponse(Socks5CommandStatus.HOST_UNREACHABLE, addressType)).addListener(ChannelFutureListener.CLOSE);
            }
        }).channel().closeFuture().addListener(f -> {
            if (requestCtx.channel().isActive()) {
                log.info("Connection to {}:{} closed", address, port);
                Channels.closeOnFlush(requestCtx.channel());
            }
        });
    }

    private static boolean nullSafeEquals(Object a, Object b) {
        return (a == b) || (a != null && a.equals(b));
    }
}

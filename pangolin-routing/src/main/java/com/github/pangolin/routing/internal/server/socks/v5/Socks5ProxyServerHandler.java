package com.github.pangolin.routing.internal.server.socks.v5;

import com.github.pangolin.handler.TcpInboundRedirectHandler;
import com.github.pangolin.util.Channels;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelPipeline;
import io.netty.handler.codec.ByteToMessageDecoder;
import io.netty.handler.codec.socksx.v5.DefaultSocks5CommandResponse;
import io.netty.handler.codec.socksx.v5.DefaultSocks5InitialResponse;
import io.netty.handler.codec.socksx.v5.DefaultSocks5PasswordAuthResponse;
import io.netty.handler.codec.socksx.v5.Socks5AddressType;
import io.netty.handler.codec.socksx.v5.Socks5AuthMethod;
import io.netty.handler.codec.socksx.v5.Socks5CommandRequest;
import io.netty.handler.codec.socksx.v5.Socks5CommandRequestDecoder;
import io.netty.handler.codec.socksx.v5.Socks5CommandStatus;
import io.netty.handler.codec.socksx.v5.Socks5CommandType;
import io.netty.handler.codec.socksx.v5.Socks5InitialRequest;
import io.netty.handler.codec.socksx.v5.Socks5InitialRequestDecoder;
import io.netty.handler.codec.socksx.v5.Socks5Message;
import io.netty.handler.codec.socksx.v5.Socks5PasswordAuthRequest;
import io.netty.handler.codec.socksx.v5.Socks5PasswordAuthRequestDecoder;
import io.netty.handler.codec.socksx.v5.Socks5PasswordAuthStatus;
import io.netty.handler.codec.socksx.v5.Socks5ServerEncoder;
import io.netty.util.ReferenceCountUtil;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class Socks5ProxyServerHandler extends ChannelInboundHandlerAdapter {
    private static final String DEFAULT_DECODER_NAME = "Socks5ServerDecoder";

    private String decoderName = DEFAULT_DECODER_NAME;
    private final String username;
    private final String password;

    public Socks5ProxyServerHandler() {
        this(null, null);
    }

    public Socks5ProxyServerHandler(final String username, final String password) {
        this.username = username;
        this.password = password;
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
        /*-
        <pre>
        if (isHasAuthorization()) {
            cp.addBefore(ctx.name(), null, new Socks5PasswordAuthRequestDecoder());
        } else {
            cp.addBefore(ctx.name(), null, new Socks5CommandRequestDecoder());
        }
        </pre>
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
            if (!(msg instanceof Socks5Message) || !((Socks5Message) msg).decoderResult().isSuccess()) {
                log.error("Connection closed by UNKNOWN message: {}", msg.getClass().getName());
                ctx.channel().writeAndFlush(Unpooled.EMPTY_BUFFER).addListener(ChannelFutureListener.CLOSE);
                return;
            }
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
                    connect(ctx, request).addListener(new ChannelFutureListener() {
                        @Override
                        public void operationComplete(final ChannelFuture future) throws Exception {
                            if (future.isSuccess()) {
                                log.info("Connection established: {}", future.channel().remoteAddress());
                                ctx.writeAndFlush(new DefaultSocks5CommandResponse(Socks5CommandStatus.SUCCESS, addressType)).addListener(removeOnComplete(ctx, Socks5ServerEncoder.DEFAULT));
                            } else {
                                if (log.isDebugEnabled()) {
                                    log.debug("Failed to Connect to {}: {}", future.channel().remoteAddress(), future.cause().getMessage(), future.cause());
                                }
                                log.warn("Failed to Connect to {}: {}", future.channel().remoteAddress(), future.cause().getMessage());
                                ctx.writeAndFlush(new DefaultSocks5CommandResponse(Socks5CommandStatus.HOST_UNREACHABLE, addressType)).addListener(ChannelFutureListener.CLOSE);
                            }
                        }
                    }).channel().closeFuture().addListener(new ChannelFutureListener() {
                        @Override
                        public void operationComplete(final ChannelFuture future) throws Exception {
                            if (ctx.channel().isActive()) {
                                log.info("Connection to {} closed", future.channel().remoteAddress());
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

    protected ChannelFuture connect(final ChannelHandlerContext ctx, final Socks5CommandRequest request) throws Exception {
        final int port = request.dstPort();
        final String address = request.dstAddr();
        final Socks5AddressType addressType = request.dstAddrType();

        ctx.channel().config().setAutoRead(false);
        return Channels.open(address, port, false, ctx.channel().eventLoop(), new ChannelInboundHandlerAdapter() {
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

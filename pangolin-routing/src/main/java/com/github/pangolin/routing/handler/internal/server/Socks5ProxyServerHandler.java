package com.github.pangolin.routing.handler.internal.server;

import com.github.pangolin.handler.TcpInboundRedirectHandler;
import com.github.pangolin.routing.handler.internal.server.support.SocketChannelFactory;
import com.github.pangolin.routing.handler.internal.server.support.StandardSocketChannelFactory;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.handler.codec.ByteToMessageDecoder;
import io.netty.handler.codec.socksx.v5.*;
import io.netty.util.ReferenceCountUtil;
import lombok.extern.slf4j.Slf4j;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.List;

/**
 *
 */
@Slf4j
public class Socks5ProxyServerHandler extends ChannelInboundHandlerAdapter {
    private static final String DEFAULT_DECODER_NAME = "Socks5ServerDecoder";

    private String decoderName = DEFAULT_DECODER_NAME;

    private final String username;
    private final String password;
    private final SocketChannelFactory socketChannelFactory;

    public Socks5ProxyServerHandler() {
        this(null, null, new StandardSocketChannelFactory());
    }

    public Socks5ProxyServerHandler(final String username, final String password) {
        this(username, password, new StandardSocketChannelFactory());
    }

    public Socks5ProxyServerHandler(final String username, final String password, final SocketChannelFactory socketChannelFactory) {
        this.username = username;
        this.password = password;
        this.socketChannelFactory = socketChannelFactory;
    }


    @Override
    public void handlerAdded(final ChannelHandlerContext ctx) throws Exception {
        final ChannelPipeline cp = ctx.pipeline();
        final Socks5InitialRequestDecoder decoder = cp.get(Socks5InitialRequestDecoder.class);
        if (null != decoder) {
            decoderName = cp.context(decoder).name();
        } else {
            cp.addBefore(ctx.name(), decoderName, new Socks5InitialRequestDecoder());
        }
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
                log.warn("[SOCKS5] Connection closed by UNKNOWN message '{}'", msg.getClass().getName());
                ctx.channel().writeAndFlush(Unpooled.EMPTY_BUFFER).addListener(ChannelFutureListener.CLOSE);
                return;
            }

            final SocketAddress clientAddress = ctx.channel().remoteAddress();
            if (msg instanceof Socks5InitialRequest) {
                final List<Socks5AuthMethod> methods = ((Socks5InitialRequest) msg).authMethods();
                log.debug("[SOCKS5] Received INIT request from {}, methods: {}", clientAddress, methods);

                final boolean needAuth = this.isHasAuthorization();
                final ByteToMessageDecoder newDecoder = needAuth ? new Socks5PasswordAuthRequestDecoder() : new Socks5CommandRequestDecoder();
                ctx.pipeline().replace(decoderName, decoderName, newDecoder);
                ctx.writeAndFlush(new DefaultSocks5InitialResponse(isHasAuthorization() ? Socks5AuthMethod.PASSWORD : Socks5AuthMethod.NO_AUTH));
            } else if (msg instanceof Socks5PasswordAuthRequest) {
                final Socks5PasswordAuthRequest request = (Socks5PasswordAuthRequest) msg;
                log.debug("[SOCKS5] Received AUTH request from {}, username: {}, password: {}", clientAddress, request.username(), request.password());

                if (nullSafeEquals(this.username, request.username()) && nullSafeEquals(password, request.password())) {
                    ctx.pipeline().replace(decoderName, decoderName, new Socks5CommandRequestDecoder());

                    log.debug("[SOCKS5] Respond authenticated to {}", clientAddress);

                    ctx.writeAndFlush(new DefaultSocks5PasswordAuthResponse(Socks5PasswordAuthStatus.SUCCESS));
                } else {
                    log.warn("[SOCKS5] Respond not permitted to {}", clientAddress);

                    ctx.writeAndFlush(new DefaultSocks5PasswordAuthResponse(Socks5PasswordAuthStatus.FAILURE));
                }
            } else if (msg instanceof Socks5CommandRequest) {
                final Socks5CommandRequest request = (Socks5CommandRequest) msg;
                final Socks5CommandType type = request.type();

                final int port = request.dstPort();
                final String address = request.dstAddr();
                final Socks5AddressType addressType = request.dstAddrType();

                log.info("[SOCKS5] Received {} request => {}:{}", type.toString(), address, port);

                if (Socks5CommandType.CONNECT.equals(type)) {
                    connect(ctx, request).addListener(new ChannelFutureListener() {
                        @Override
                        public void operationComplete(final ChannelFuture future) throws Exception {
                            if (future.isSuccess()) {
                                log.debug("[SOCKS5] Connection established => {}:{}", address, port);
                                ctx.writeAndFlush(
                                        new DefaultSocks5CommandResponse(Socks5CommandStatus.SUCCESS, addressType)
                                ).addListener(removeOnComplete(ctx, Socks5ServerEncoder.DEFAULT));
                            } else {
                                log.error("[SOCKS5] Error: {} => {}:{}", future.cause().getMessage(), address, port);
                                ctx.writeAndFlush(
                                        new DefaultSocks5CommandResponse(Socks5CommandStatus.HOST_UNREACHABLE, addressType)
                                ).addListener(ChannelFutureListener.CLOSE);
                            }
                        }
                    }).channel().closeFuture().addListener(new ChannelFutureListener() {
                        @Override
                        public void operationComplete(final ChannelFuture future) throws Exception {
                            if (ctx.channel().isActive()) {
                                ctx.channel().writeAndFlush(Unpooled.EMPTY_BUFFER).addListener(ChannelFutureListener.CLOSE);
                            }
                            log.info("[SOCKS5] Connection closed => {}:{}", address, port);
                        }
                    });
                } else {
                    /*
                    if (Socks5CommandType.UDP_ASSOCIATE.equals(type)) {
                        InetSocketAddress sa = (InetSocketAddress) ctx.channel().localAddress();
                        if (sa.isUnresolved()) {
                            ctx.writeAndFlush(new DefaultSocks5CommandResponse(Socks5CommandStatus.SUCCESS, Socks5AddressType.DOMAIN, sa.getHostString(), 1080));
                            return;
                        }
                        InetAddress address = sa.getAddress();
                        if (address instanceof Inet4Address) {
                            Inet4Address a = (Inet4Address) address;
                            ctx.writeAndFlush(new DefaultSocks5CommandResponse(Socks5CommandStatus.SUCCESS, Socks5AddressType.IPv4, a.getHostAddress(), 1080));
                            return;
                        }
                        if (address instanceof Inet6Address) {
                            Inet6Address a = (Inet6Address) address;
                            ctx.writeAndFlush(new DefaultSocks5CommandResponse(Socks5CommandStatus.SUCCESS, Socks5AddressType.IPv6, a.getHostAddress(), 1080));
                            return;
                        }
                    }
                    */
                    log.warn("[SOCKS5] Connection closed: '{}' unsupported => {}:{}", type, address, port);
                    ctx.writeAndFlush(new DefaultSocks5CommandResponse(Socks5CommandStatus.COMMAND_UNSUPPORTED, addressType)).addListener(ChannelFutureListener.CLOSE);
                }
            } else {
                log.error("[SOCKS5] Connection closed by UNKNOWN message '{}'", msg.getClass().getName());
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
                if (null != ctx.pipeline().context(h)) {
                    ctx.pipeline().remove(h);
                }
            }
        };
    }

    @Override
    public void exceptionCaught(final ChannelHandlerContext ctx, final Throwable cause) throws Exception {
        log.error("[SOCKS5] Software caused connection abort: {}", cause.getMessage(), cause);
        ctx.channel().writeAndFlush(Unpooled.EMPTY_BUFFER).addListener(ChannelFutureListener.CLOSE);
    }

    protected ChannelFuture connect(final ChannelHandlerContext ctx, final Socks5CommandRequest request) throws Exception {
        final int port = request.dstPort();
        final String address = request.dstAddr();
        final Socks5AddressType addressType = request.dstAddrType();

        ctx.channel().config().setAutoRead(false);

        final ChannelConfig c = ctx.channel().config();
        return socketChannelFactory.open(new InetSocketAddress(address, port), c.getConnectTimeoutMillis(), false, ctx.channel().eventLoop(), new ChannelInboundHandlerAdapter() {
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

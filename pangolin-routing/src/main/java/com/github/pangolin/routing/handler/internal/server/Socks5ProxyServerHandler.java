package com.github.pangolin.routing.handler.internal.server;

import com.github.pangolin.handler.TcpInboundRedirectHandler;
import com.github.pangolin.routing.handler.internal.server.support.SocketChannelFactory;
import com.github.pangolin.routing.handler.internal.server.support.StandardSocketChannelFactory;
import com.github.pangolin.routing.util.SocketUtils;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelConfig;
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
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.GenericFutureListener;
import lombok.extern.slf4j.Slf4j;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.function.Supplier;

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
    private final Supplier<Socks5DatagramServerHandler> udpServerFactory;

    public Socks5ProxyServerHandler() {
        this(null);
    }

    public Socks5ProxyServerHandler(final Supplier<Socks5DatagramServerHandler> datagramServerFactory) {
        this(null, null, datagramServerFactory);
    }

    public Socks5ProxyServerHandler(final String username, final String password, final SocketChannelFactory socketChannelFactory) {
        this(username, password, socketChannelFactory, null);
    }

    public Socks5ProxyServerHandler(final String username, final String password, final Supplier<Socks5DatagramServerHandler> udpServerFactory) {
        this(username, password, new StandardSocketChannelFactory(), udpServerFactory);
    }

    public Socks5ProxyServerHandler(final String username, final String password, final SocketChannelFactory socketChannelFactory,
                                    final Supplier<Socks5DatagramServerHandler> udpServerFactory) {
        this.username = username;
        this.password = password;
        this.socketChannelFactory = socketChannelFactory;
        this.udpServerFactory = udpServerFactory;
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

            final InetSocketAddress clientAddress = (InetSocketAddress) ctx.channel().remoteAddress();
            if (msg instanceof Socks5InitialRequest) {
                final List<Socks5AuthMethod> methods = ((Socks5InitialRequest) msg).authMethods();
                log.debug("[SOCKS5] Received {} INIT request, methods: {}", clientAddress, methods);

                final boolean needAuth = this.isHasAuthorization();
                final ByteToMessageDecoder newDecoder = needAuth ? new Socks5PasswordAuthRequestDecoder() : new Socks5CommandRequestDecoder();
                ctx.pipeline().replace(decoderName, decoderName, newDecoder);
                ctx.writeAndFlush(new DefaultSocks5InitialResponse(isHasAuthorization() ? Socks5AuthMethod.PASSWORD : Socks5AuthMethod.NO_AUTH));
            } else if (msg instanceof Socks5PasswordAuthRequest) {
                final Socks5PasswordAuthRequest request = (Socks5PasswordAuthRequest) msg;
                log.debug("[SOCKS5] Received {} AUTH request, username: {}, password: {}", clientAddress, request.username(), request.password());

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

                log.info("[SOCKS5] Received {} {} request => {}:{}", clientAddress, type.toString(), address, port);

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
                                log.error("[SOCKS5] Error: {}/{} => {}:{}", future.cause().getMessage(), future.cause().getClass().getSimpleName(), address, port);
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
                    if (Socks5CommandType.UDP_ASSOCIATE.equals(type)) {
                        final InetSocketAddress serverAddress = (InetSocketAddress) ctx.channel().localAddress();
                        final String udpServerHost = serverAddress.getHostString();
                        final int udpServerPort = serverAddress.getPort();

                        if (null != udpServerFactory) {
                            final Socks5DatagramServerHandler udpServerHandler = udpServerFactory.get();
                            ctx.channel().closeFuture().addListener(new GenericFutureListener<Future<? super Void>>() {
                                @Override
                                public void operationComplete(final Future<? super Void> future) throws Exception {
                                    udpServerHandler.removeFromWhitelist(clientAddress);
                                }
                            });

                            if (serverAddress.isUnresolved()) {
                                udpServerHandler.addToWhitelist(clientAddress);
                                ctx.writeAndFlush(new DefaultSocks5CommandResponse(Socks5CommandStatus.SUCCESS, Socks5AddressType.DOMAIN, udpServerHost, udpServerPort));
                                return;
                            }
                            InetAddress inetAddress = serverAddress.getAddress();
                            if (inetAddress instanceof Inet4Address) {
                                udpServerHandler.addToWhitelist(clientAddress);
                                ctx.writeAndFlush(new DefaultSocks5CommandResponse(Socks5CommandStatus.SUCCESS, Socks5AddressType.IPv4, udpServerHost, udpServerPort));
                                return;
                            }
                            if (inetAddress instanceof Inet6Address) {
                                udpServerHandler.addToWhitelist(clientAddress);
                                ctx.writeAndFlush(new DefaultSocks5CommandResponse(Socks5CommandStatus.SUCCESS, Socks5AddressType.IPv6, udpServerHost, udpServerPort));
                                return;
                            }
                        }
                    }
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
//        log.error("[SOCKS5] Software caused connection abort: {}", cause.getMessage(), cause);
        log.error("[SOCKS5] Software caused connection abort: {}", cause.getMessage(), cause);
    }

    protected ChannelFuture connect(final ChannelHandlerContext ctx, final Socks5CommandRequest request) throws Exception {
        final int port = request.dstPort();
        final String address = request.dstAddr();
        final Socks5AddressType addressType = request.dstAddrType();

        ctx.channel().config().setAutoRead(false);

        final ChannelConfig c = ctx.channel().config();
        final InetSocketAddress addr = SocketUtils.toSocketAddress(address, port, false);
        return socketChannelFactory.open(addr, c.getConnectTimeoutMillis(), false, ctx.channel().eventLoop(), new ChannelInboundHandlerAdapter() {
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

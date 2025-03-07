package com.github.pangolin.routing.handler.internal.server;

import com.github.pangolin.handler.TcpInboundRedirectHandler;
import com.github.pangolin.routing.support.SocketChannelFactory;
import com.github.pangolin.routing.support.StandardSocketChannelFactory;
import com.github.pangolin.routing.util.SocketUtils;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.handler.codec.ByteToMessageDecoder;
import io.netty.handler.codec.socksx.v5.*;
import io.netty.util.AttributeKey;
import io.netty.util.ReferenceCountUtil;
import lombok.extern.slf4j.Slf4j;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.List;

/**
 * @see <a href="https://datatracker.ietf.org/doc/html/rfc1928">RFC 1928</a>
 */
@Slf4j
public class Socks5ProxyServerHandler extends ChannelInboundHandlerAdapter {
    private static final String DEFAULT_DECODER_NAME = "Socks5ServerDecoder";
    public static final AttributeKey<ChannelFuture> UDP_CHANNEL_KEY = AttributeKey.valueOf("Socks5UDP");

    private String decoderName = DEFAULT_DECODER_NAME;

    private final String username;
    private final String password;
    private final SocketChannelFactory socketChannelFactory;

    public Socks5ProxyServerHandler() {
        this(null, null);
    }

    public Socks5ProxyServerHandler(final String username, final String password) {
        this(username, password, new StandardSocketChannelFactory(null));
    }


    public Socks5ProxyServerHandler(final String username, final String password,
                                    final SocketChannelFactory socketChannelFactory) {
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

                final Socks5AddressType addressType = request.dstAddrType();
                final InetSocketAddress destAddress = SocketUtils.toSocketAddress(request.dstAddr(), request.dstPort(), false);
                final String destAddressName = String.format("%s:%s", destAddress.getHostString(), destAddress.getPort());

                log.info("[SOCKS5] Received {} {} request => {}", clientAddress, type.toString(), destAddressName);

                if (Socks5CommandType.CONNECT.equals(type)) {
                    connect(ctx, destAddress).addListener(new ChannelFutureListener() {
                        @Override
                        public void operationComplete(final ChannelFuture future) throws Exception {
                            if (future.isSuccess()) {
                                log.debug("[SOCKS5] Connection established => {}", destAddressName);
                                ctx.writeAndFlush(
                                        new DefaultSocks5CommandResponse(Socks5CommandStatus.SUCCESS, addressType)
                                ).addListener(removeOnComplete(ctx, Socks5ServerEncoder.DEFAULT));
                            } else {
                                log.error("[SOCKS5] Error: {}/{} => {}", future.cause().getMessage(), future.cause().getClass().getSimpleName(), destAddressName);
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
                            log.info("[SOCKS5] Connection closed => {}", destAddressName);
                        }
                    });
                } else if (!Socks5CommandType.UDP_ASSOCIATE.equals(type) || !udpAssociate(ctx, request)) {
                    log.warn("[SOCKS5] Connection closed: '{}' unsupported => {}", type, destAddressName);
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

    protected ChannelFuture connect(final ChannelHandlerContext ctx, final InetSocketAddress addr) throws Exception {
        ctx.channel().config().setAutoRead(false);

        final ChannelConfig c = ctx.channel().config();
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

    protected boolean udpAssociate(final ChannelHandlerContext ctx, final Socks5CommandRequest request) {
        final ChannelFuture udpServer = ctx.channel().parent().attr(UDP_CHANNEL_KEY).get();
        if (null != udpServer) {
            udpServer.addListener(new ChannelFutureListener() {
                @Override
                public void operationComplete(ChannelFuture future) throws Exception {
                    if (!future.isSuccess()) {
                        ctx.writeAndFlush(new DefaultSocks5CommandResponse(Socks5CommandStatus.FAILURE, request.dstAddrType()));
                        return;
                    }

                    final Channel udpChannel = future.channel();
                    final Socks5ServerDatagramDemultiplexer demultiplexer = udpChannel.pipeline().get(Socks5ServerDatagramDemultiplexer.class);
                    if (null == demultiplexer) {
                        ctx.writeAndFlush(new DefaultSocks5CommandResponse(Socks5CommandStatus.FAILURE, request.dstAddrType()));
                        // CLOSE ?
                        return;
                    }

                    final InetSocketAddress tcpServerAddress = (InetSocketAddress) ctx.channel().localAddress();
                    final InetSocketAddress tcpClientAddress = (InetSocketAddress) ctx.channel().remoteAddress();
                    final InetSocketAddress udpServerAddress = (InetSocketAddress) udpChannel.localAddress();
                    final String udpServerHost = tcpServerAddress.getHostString();
                    final int udpServerPort = udpServerAddress.getPort();

                    ctx.pipeline().remove(ctx.handler());
                    demultiplexer.join(tcpClientAddress);

                    ctx.channel().closeFuture().addListener(new ChannelFutureListener() {
                        @Override
                        public void operationComplete(ChannelFuture future) throws Exception {
                            demultiplexer.leave(tcpClientAddress);
                        }
                    });
                    if (tcpServerAddress.isUnresolved()) {
                        log.info("[SOCKS5] UDP server {}:{} -> {}", udpServerHost, udpServerPort, tcpClientAddress);
                        ctx.writeAndFlush(new DefaultSocks5CommandResponse(Socks5CommandStatus.SUCCESS, Socks5AddressType.DOMAIN, udpServerHost, udpServerPort));
                        return;
                    }
                    InetAddress inetAddress = tcpServerAddress.getAddress();
                    if (inetAddress instanceof Inet4Address) {
                        log.info("[SOCKS5] UDP server {}:{} -> {}", udpServerHost, udpServerPort, tcpClientAddress);
                        ctx.writeAndFlush(new DefaultSocks5CommandResponse(Socks5CommandStatus.SUCCESS, Socks5AddressType.IPv4, udpServerHost, udpServerPort));
                        return;
                    }
                    if (inetAddress instanceof Inet6Address) {
                        log.info("[SOCKS5] UDP server {}:{} -> {}", udpServerHost, udpServerPort, tcpClientAddress);
                        ctx.writeAndFlush(new DefaultSocks5CommandResponse(Socks5CommandStatus.SUCCESS, Socks5AddressType.IPv6, udpServerHost, udpServerPort));
                        return;
                    }
                    ctx.writeAndFlush(new DefaultSocks5CommandResponse(Socks5CommandStatus.FAILURE, request.dstAddrType()));
                }
            });
            return true;
        }
        return false;
    }

}

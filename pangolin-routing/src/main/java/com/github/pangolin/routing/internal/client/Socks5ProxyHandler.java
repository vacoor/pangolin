package com.github.pangolin.routing.internal.client;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.ChannelPromise;
import io.netty.handler.codec.socksx.v5.*;
import io.netty.util.NetUtil;

import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 *
 */
public class Socks5ProxyHandler extends ProxyHandler {
    private static final String NONE = "";
    private static final String SOCKS5_DECODER_NAME = "SOCKS5_DECODER";

    private String username;
    private String password;

    public Socks5ProxyHandler(final SocketAddress proxyServerAddress) {
        this(proxyServerAddress, null, null);
    }

    public Socks5ProxyHandler(final SocketAddress proxyServerAddress,
                              final String username, final String password) {
        super(proxyServerAddress);
        this.username = username;
        this.password = password;
    }

    @Override
    public void handlerAdded(final ChannelHandlerContext ctx) throws Exception {
        final ChannelPipeline cp = ctx.pipeline();
        cp.addBefore(ctx.name(), SOCKS5_DECODER_NAME, new Socks5InitialResponseDecoder());
        cp.addBefore(ctx.name(), null, Socks5ClientEncoder.DEFAULT);
        super.handlerAdded(ctx);
    }

    @Override
    protected ChannelPromise handshake(final ChannelHandlerContext ctx, final ChannelPromise promise) throws Exception {
        ctx.writeAndFlush(createInitialRequest(), promise);
        return promise;
    }

    @Override
    protected boolean handshakeRead(final ChannelHandlerContext ctx, final Object msg) throws Exception {
        if (msg instanceof Socks5InitialResponse) {
            final Socks5InitialResponse socks5InitialResponse = (Socks5InitialResponse) msg;
            final Socks5AuthMethod socks5AuthMethod = socks5InitialResponse.authMethod();
            if (Socks5AuthMethod.PASSWORD.equals(socks5AuthMethod)) {
                ctx.pipeline().replace(SOCKS5_DECODER_NAME, SOCKS5_DECODER_NAME, new Socks5PasswordAuthResponseDecoder());
                final String usernameToUse = null != username ? username : NONE;
                final String passwordToUse = null != password ? password : NONE;
                ctx.writeAndFlush(new DefaultSocks5PasswordAuthRequest(usernameToUse, passwordToUse));
            } else if (Socks5AuthMethod.NO_AUTH.equals(socks5AuthMethod)) {
                ctx.pipeline().replace(SOCKS5_DECODER_NAME, SOCKS5_DECODER_NAME, new Socks5CommandResponseDecoder());
                ctx.writeAndFlush(createConnectRequest(destinationAddress()));
            } else {
                throw new ConnectException("unexpected authMethod: " + socks5AuthMethod);
            }
            return false;
        }

        if (msg instanceof Socks5PasswordAuthResponse) {
            final Socks5PasswordAuthResponse socks5PasswordAuthResponse = (Socks5PasswordAuthResponse) msg;
            if (Socks5PasswordAuthStatus.SUCCESS.equals(socks5PasswordAuthResponse.status())) {
                ctx.pipeline().replace(SOCKS5_DECODER_NAME, SOCKS5_DECODER_NAME, new Socks5CommandResponseDecoder());
                ctx.writeAndFlush(createConnectRequest(destinationAddress()));
            } else {
                throw new ConnectException("Auth fail");
            }
            return false;
        }

        final Socks5CommandResponse socks5CommandResponse = (Socks5CommandResponse) msg;
        if (!Socks5CommandStatus.SUCCESS.equals(socks5CommandResponse.status())) {
            throw new ConnectException("status = " + socks5CommandResponse.status());
        }

        ctx.pipeline().remove(SOCKS5_DECODER_NAME);
        ctx.pipeline().remove(Socks5ClientEncoder.DEFAULT);

        return true;
    }

    private Socks5InitialRequest createInitialRequest() {
        final List<Socks5AuthMethod> authMethods = null == username && null == password
                ? Collections.singletonList(Socks5AuthMethod.NO_AUTH)
                : Arrays.asList(Socks5AuthMethod.PASSWORD, Socks5AuthMethod.PASSWORD);
        return new DefaultSocks5InitialRequest(authMethods);
    }

    private Socks5CommandRequest createConnectRequest(final InetSocketAddress raddr) throws Exception {
        Socks5AddressType addrType;
        String rhost;
        if (raddr.isUnresolved()) {
            addrType = Socks5AddressType.DOMAIN;
            rhost = raddr.getHostString();
        } else {
            rhost = raddr.getAddress().getHostAddress();
            if (NetUtil.isValidIpV4Address(rhost)) {
                addrType = Socks5AddressType.IPv4;
            } else if (NetUtil.isValidIpV6Address(rhost)) {
                addrType = Socks5AddressType.IPv6;
            } else {
                throw new ConnectException("unknown address type: " + raddr.getClass().getName());
            }
        }
        return new DefaultSocks5CommandRequest(Socks5CommandType.CONNECT, addrType, rhost, raddr.getPort());
    }
}

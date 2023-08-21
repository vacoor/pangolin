package com.github.pangolin.proxy.server;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPipeline;
import io.netty.handler.codec.socksx.v5.DefaultSocks5CommandRequest;
import io.netty.handler.codec.socksx.v5.DefaultSocks5InitialRequest;
import io.netty.handler.codec.socksx.v5.DefaultSocks5PasswordAuthRequest;
import io.netty.handler.codec.socksx.v5.Socks5AddressType;
import io.netty.handler.codec.socksx.v5.Socks5AuthMethod;
import io.netty.handler.codec.socksx.v5.Socks5ClientEncoder;
import io.netty.handler.codec.socksx.v5.Socks5CommandRequest;
import io.netty.handler.codec.socksx.v5.Socks5CommandResponse;
import io.netty.handler.codec.socksx.v5.Socks5CommandResponseDecoder;
import io.netty.handler.codec.socksx.v5.Socks5CommandStatus;
import io.netty.handler.codec.socksx.v5.Socks5CommandType;
import io.netty.handler.codec.socksx.v5.Socks5InitialRequest;
import io.netty.handler.codec.socksx.v5.Socks5InitialResponse;
import io.netty.handler.codec.socksx.v5.Socks5InitialResponseDecoder;
import io.netty.handler.codec.socksx.v5.Socks5PasswordAuthResponse;
import io.netty.handler.codec.socksx.v5.Socks5PasswordAuthResponseDecoder;
import io.netty.handler.codec.socksx.v5.Socks5PasswordAuthStatus;
import io.netty.util.NetUtil;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.Collections;

/**
 * TODO DOC ME!.
 *
 * @author changhe.yang
 * @since 20230821
 */
public class Socks5ProxyClientHandler extends ChannelProxyClientHandler {
    private static final String SOCKS5_DECODER_NAME = "SOCKS5_DECODER";

    public Socks5ProxyClientHandler(final SocketAddress proxyServerAddress) {
        super(proxyServerAddress);
    }

    @Override
    public void handlerAdded(final ChannelHandlerContext ctx) throws Exception {
        final ChannelPipeline cp = ctx.pipeline();
        cp.addBefore(ctx.name(), SOCKS5_DECODER_NAME, new Socks5InitialResponseDecoder());
        cp.addBefore(ctx.name(), null, Socks5ClientEncoder.DEFAULT);

        if (ctx.channel().isActive()) {
            ctx.writeAndFlush(createInitialRequest());
        }
    }

    @Override
    public void channelActive(final ChannelHandlerContext ctx) throws Exception {
        ctx.fireChannelActive();
        ctx.writeAndFlush(createInitialRequest());
    }

    @Override
    protected boolean channelRead0(final ChannelHandlerContext ctx, final Object msg) throws Exception {
        if (msg instanceof Socks5InitialResponse) {
            final Socks5InitialResponse socks5InitialResponse = (Socks5InitialResponse) msg;
            final Socks5AuthMethod socks5AuthMethod = socks5InitialResponse.authMethod();
            if (Socks5AuthMethod.PASSWORD.equals(socks5AuthMethod)) {
                ctx.pipeline().replace(SOCKS5_DECODER_NAME, SOCKS5_DECODER_NAME, new Socks5PasswordAuthResponseDecoder());
                ctx.writeAndFlush(new DefaultSocks5PasswordAuthRequest("username", "password"));
            } else if (Socks5AuthMethod.NO_AUTH.equals(socks5AuthMethod)) {
                ctx.pipeline().replace(SOCKS5_DECODER_NAME, SOCKS5_DECODER_NAME, new Socks5CommandResponseDecoder());
                ctx.writeAndFlush(createConnectRequest(getDelegateAddress()));
            } else {
                throw new UnsupportedOperationException();
            }
            return false;
        }

        if (msg instanceof Socks5PasswordAuthResponse) {
            final Socks5PasswordAuthResponse socks5PasswordAuthResponse = (Socks5PasswordAuthResponse) msg;
            if (Socks5PasswordAuthStatus.SUCCESS.equals(socks5PasswordAuthResponse.status())) {
                ctx.pipeline().replace(SOCKS5_DECODER_NAME, SOCKS5_DECODER_NAME, new Socks5CommandResponseDecoder());
                ctx.writeAndFlush(createConnectRequest(getDelegateAddress()));
            } else {
                throw new RuntimeException("Auth fail");
            }
            return false;
        }

        final Socks5CommandResponse socks5CommandResponse = (Socks5CommandResponse) msg;
        if (!Socks5CommandStatus.SUCCESS.equals(socks5CommandResponse.status())) {
            throw new RuntimeException("Auth fail");
        }

        ctx.pipeline().remove(SOCKS5_DECODER_NAME);
        ctx.pipeline().remove(Socks5ClientEncoder.DEFAULT);

        return true;
    }

    private Socks5InitialRequest createInitialRequest() {
        return new DefaultSocks5InitialRequest(Collections.singletonList(Socks5AuthMethod.NO_AUTH));
    }

    private Socks5CommandRequest createConnectRequest(final InetSocketAddress raddr) {
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
                // throw new ProxyConnectException( exceptionMessage("unknown address type: " + StringUtil.simpleClassName(rhost)));
                throw new UnsupportedOperationException();
            }
        }

        // ctx.pipeline().replace(decoderName, decoderName, new Socks5CommandResponseDecoder());
//        sendToProxyServer(new DefaultSocks5CommandRequest(Socks5CommandType.CONNECT, addrType, rhost, raddr.getPort()));
        return new DefaultSocks5CommandRequest(Socks5CommandType.CONNECT, addrType, rhost, raddr.getPort());
    }
}

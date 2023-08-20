package com.github.pangolin.proxy.bridge.socks5;

import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.socksx.v5.DefaultSocks5PasswordAuthResponse;
import io.netty.handler.codec.socksx.v5.Socks5PasswordAuthRequest;
import io.netty.handler.codec.socksx.v5.Socks5PasswordAuthRequestDecoder;
import io.netty.handler.codec.socksx.v5.Socks5PasswordAuthStatus;

import java.util.Objects;

public class Socks5PasswordAuthRequestHandler extends SimpleChannelInboundHandler<Socks5PasswordAuthRequest> {
    private final String username;
    private final String password;

    public Socks5PasswordAuthRequestHandler(final String username, final String password) {
        this.username = username;
        this.password = password;
    }

    @Override
    public void handlerAdded(final ChannelHandlerContext ctx) throws Exception {
        if (null == ctx.pipeline().get(Socks5PasswordAuthRequestDecoder.class)) {
            ctx.pipeline().addBefore(ctx.name(), null, new Socks5PasswordAuthRequestDecoder());
        }
    }

    @Override
    public void handlerRemoved(final ChannelHandlerContext ctx) throws Exception {
        ctx.pipeline().remove(Socks5PasswordAuthRequestDecoder.class);
    }

    @Override
    protected void channelRead0(final ChannelHandlerContext ctx, final Socks5PasswordAuthRequest msg) throws Exception {
        if (Objects.equals(username, msg.username()) && Objects.equals(password, msg.password())) {
            ctx.writeAndFlush(new DefaultSocks5PasswordAuthResponse(Socks5PasswordAuthStatus.SUCCESS));
        } else {
            ctx.writeAndFlush(new DefaultSocks5PasswordAuthResponse(Socks5PasswordAuthStatus.FAILURE)).addListener(ChannelFutureListener.CLOSE);
        }
    }

}
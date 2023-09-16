package com.github.pangolin.routing.internal.server.ss.codec;

import com.github.pangolin.handler.TcpInboundRedirectHandler;
import com.github.pangolin.routing.RoutingRule;
import com.github.pangolin.routing.internal.server.socks.v5.Socks5ProxyServerHandler;
import com.github.pangolin.routing.internal.server.ss.ShadowsocksKeyFactory;
import com.github.pangolin.util.Channels;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.codec.socksx.v5.Socks5CommandRequest;
import io.netty.resolver.NoopAddressResolverGroup;
import lombok.extern.slf4j.Slf4j;

import javax.crypto.SecretKey;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.security.SecureRandom;
import java.util.List;

/**
 *
 */
@Slf4j
public class Socks5RoutingServerHandler extends Socks5ProxyServerHandler {
    private final List<RoutingRule> routingRules;

    public Socks5RoutingServerHandler(final List<RoutingRule> routingRules) {
        this.routingRules = routingRules;
    }

    @Override
    protected ChannelFuture connect(final ChannelHandlerContext ctx, final Socks5CommandRequest request) throws Exception {
        ctx.channel().config().setAutoRead(false);

        final InetSocketAddress destinationAddress = new InetSocketAddress(request.dstAddr(), request.dstPort());
        final ChannelHandler networkHandler = this.select(destinationAddress);

        return Channels.open(destinationAddress, NoopAddressResolverGroup.INSTANCE, false, ctx.channel().eventLoop(), new ChannelInitializer<SocketChannel>() {
            @Override
            protected void initChannel(final SocketChannel ch) throws Exception {
                if (null != networkHandler) {
                    final SecretKey key = ShadowsocksKeyFactory.generateKey("AES", 16, "000000");
                    ch.pipeline().addLast(new ShadowsocksAeadEncoder(key.getEncoded()));
                    ch.pipeline().addLast(new ShadowsocksAeadDecoder(key.getEncoded()));
                    ch.pipeline().addLast(networkHandler);
                }
                ch.pipeline().addLast(new ChannelInboundHandlerAdapter() {
                    @Override
                    public void channelRegistered(final ChannelHandlerContext delegateCtx) throws Exception {
                        delegateCtx.pipeline().replace(this, null, new TcpInboundRedirectHandler(ctx));
                        ctx.pipeline().replace(ctx.handler(), null, new TcpInboundRedirectHandler(delegateCtx));

                        delegateCtx.channel().config().setAutoRead(true);
                        ctx.channel().config().setAutoRead(true);
                    }
                });
            }
        });
    }

    private static String getAlgorithm(final String transformation) {
        final int i = null != transformation ? transformation.indexOf('/') : -1;
        return -1 < i ? transformation.substring(0, i) : transformation;
    }

    private ChannelHandler select(final SocketAddress destinationAddress) {
        if (null == routingRules || !(destinationAddress instanceof InetSocketAddress)) {
            return null;
        }
        if (true) {
            return routingRules.iterator().next().newProxyHandler();
        }
        final InetSocketAddress sa = (InetSocketAddress) destinationAddress;
        for (final RoutingRule routing : routingRules) {
            if (routing.matches(sa)) {
                return routing.newProxyHandler();
            }
        }
        return null;
    }
}

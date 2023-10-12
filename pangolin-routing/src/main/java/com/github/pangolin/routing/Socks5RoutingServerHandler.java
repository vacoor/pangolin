package com.github.pangolin.routing;

import com.github.pangolin.handler.TcpInboundRedirectHandler;
import com.github.pangolin.routing.internal.server.socks.v5.Socks5ProxyServerHandler;
import com.github.pangolin.routing.pattern.DestinationPattern;
import com.github.pangolin.util.Channels;
import io.netty.channel.*;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.codec.socksx.v5.Socks5CommandRequest;
import io.netty.resolver.NoopAddressResolverGroup;
import lombok.extern.slf4j.Slf4j;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.Map;

/**
 *
 */
@Slf4j
public class Socks5RoutingServerHandler extends Socks5ProxyServerHandler {
    private final Map<DestinationPattern, ? extends ProxyHandlerFactory> routingRules;

    public Socks5RoutingServerHandler(final Map<DestinationPattern, ? extends ProxyHandlerFactory> routingRules) {
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
                    ch.pipeline().addFirst(networkHandler);
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

    private ChannelHandler select(final SocketAddress destinationAddress) {
        if (null == routingRules || !(destinationAddress instanceof InetSocketAddress)) {
            return null;
        }
        final InetSocketAddress sa = (InetSocketAddress) destinationAddress;
        for (Map.Entry<DestinationPattern, ? extends ProxyHandlerFactory> entry : routingRules.entrySet()) {
            if (entry.getKey().matches(sa)) {
                final ProxyHandlerFactory value = entry.getValue();
                log.info("{} -> {}", destinationAddress, "PROXY:" + value);
                return entry.getValue().newProxyHandler();
            }
        }
        return null;
    }
}

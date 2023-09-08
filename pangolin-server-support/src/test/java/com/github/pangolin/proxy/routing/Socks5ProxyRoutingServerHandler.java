package com.github.pangolin.proxy.routing;

import com.github.pangolin.handler.TcpInboundRedirectHandler;
import com.github.pangolin.proxy.client.WebSocketProxyHandler;
import com.github.pangolin.proxy.routing.pattern.DomainPattern;
import com.github.pangolin.proxy.routing.pattern.InetSubnetCondition;
import com.github.pangolin.proxy.server.socks.v5.Socks5ProxyServerHandler;
import com.github.pangolin.server.NettyServer;
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

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.URI;
import java.util.Arrays;
import java.util.List;
import java.util.function.Supplier;

/**
 *
 */
@Slf4j
public class Socks5ProxyRoutingServerHandler extends Socks5ProxyServerHandler {
    private final List<RoutingRule> routings;

    public Socks5ProxyRoutingServerHandler(final List<RoutingRule> routings) {
        this.routings = routings;
    }

    @Override
    protected ChannelFuture connect(final ChannelHandlerContext ctx, final Socks5CommandRequest request) throws Exception {
        ctx.channel().config().setAutoRead(false);

        final InetSocketAddress destinationAddress = new InetSocketAddress(request.dstAddr(), request.dstPort());
        final ChannelHandler handler = select(destinationAddress);
        return Channels.open(destinationAddress, NoopAddressResolverGroup.INSTANCE, false, ctx.channel().eventLoop(), new ChannelInitializer<SocketChannel>() {
            @Override
            protected void initChannel(final SocketChannel ch) throws Exception {
                if (null != handler) {
                    ch.pipeline().addFirst(handler);
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
        if (null == routings || !(destinationAddress instanceof InetSocketAddress)) {
            return null;
        }
        final InetSocketAddress sa = (InetSocketAddress) destinationAddress;
        for (final RoutingRule routing : routings) {
            if (routing.matches(sa)) {
                return routing.newProxyHandler();
            }
        }
        return null;
    }
}

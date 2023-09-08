package com.github.pangolin.proxy.routing;

import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import lombok.extern.slf4j.Slf4j;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.List;

/**
 *
 */
@Slf4j
public class ProxyRoutingHandler extends ChannelOutboundHandlerAdapter {
    private final List<RoutingRule> routings;

    public ProxyRoutingHandler(final List<RoutingRule> routings) {
        this.routings = routings;
    }

    @Override
    public void connect(final ChannelHandlerContext ctx, final SocketAddress remoteAddress, final SocketAddress localAddress, final ChannelPromise promise) throws Exception {
        final ChannelHandler handlerToUse = select(remoteAddress);
        if (null != handlerToUse) {
            log.debug("Routing -> {}", handlerToUse);
            ctx.pipeline().addBefore(ctx.name(), null, handlerToUse);
            ctx.connect(remoteAddress, localAddress, promise);
            ctx.pipeline().remove(this);
        } else {
            ctx.connect(remoteAddress, localAddress, promise);
        }
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

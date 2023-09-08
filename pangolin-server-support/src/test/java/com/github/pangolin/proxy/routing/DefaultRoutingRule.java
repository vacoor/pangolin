package com.github.pangolin.proxy.routing;

import com.github.pangolin.proxy.routing.pattern.Condition;
import io.netty.channel.ChannelHandler;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.function.Supplier;

/**
 */
public class DefaultRoutingRule implements RoutingRule {
    private final Condition<InetSocketAddress> pattern;
    private final Supplier<ChannelHandler> factory;

    public DefaultRoutingRule(final Condition<InetSocketAddress> pattern, final Supplier<ChannelHandler> factory) {
        this.pattern = pattern;
        this.factory = factory;
    }

    @Override
    public boolean matches(final InetSocketAddress destinationAddress) {
        return pattern.matches(destinationAddress);
    }

    @Override
    public ChannelHandler newProxyHandler() {
        return factory.get();
    }
}

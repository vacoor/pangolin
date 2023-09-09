package com.github.pangolin.routing;

import com.github.pangolin.routing.pattern.Pattern;
import io.netty.channel.ChannelHandler;

import java.net.InetSocketAddress;
import java.util.function.Supplier;

/**
 */
public class DefaultRoutingRule implements RoutingRule {
    private final Pattern<InetSocketAddress> pattern;
    private final Supplier<ChannelHandler> factory;

    public DefaultRoutingRule(final Pattern<InetSocketAddress> pattern, final Supplier<ChannelHandler> factory) {
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

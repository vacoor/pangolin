package com.github.pangolin.routing;

import com.github.pangolin.routing.pattern.DestinationPattern;
import io.netty.channel.ChannelHandler;

import java.net.InetSocketAddress;
import java.util.function.Supplier;

/**
 *
 */
public class RoutingRule {
    private final DestinationPattern pattern;
    private final Supplier<ChannelHandler> factory;

    public RoutingRule(final DestinationPattern pattern, final Supplier<ChannelHandler> factory) {
        this.pattern = pattern;
        this.factory = factory;
    }

    public boolean matches(final InetSocketAddress destinationAddress) {
        return pattern.matches(destinationAddress);
    }

    public ChannelHandler newProxyHandler() {
        return factory.get();
    }

}

package com.github.pangolin.routing.node;

import com.github.pangolin.routing.pattern.DestinationPattern;
import io.netty.channel.ChannelHandler;

import java.util.Map;

public interface ServerRouter {

    ServerRouter addServer(final String name, final HealthProxyServer server);

    ServerRouter addRouting(final DestinationPattern pattern, final String serverName);

    Map<DestinationPattern, HealthProxyServer> getRoutingRules();

}

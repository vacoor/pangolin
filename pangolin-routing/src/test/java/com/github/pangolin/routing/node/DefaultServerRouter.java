package com.github.pangolin.routing.node;

import com.github.pangolin.routing.pattern.DestinationPattern;
import io.netty.channel.ChannelHandler;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.Map;

public class DefaultServerRouter implements ServerRouter {
    private Map<String, HealthProxyServer> servers;
    private Map<DestinationPattern, HealthProxyServer> routing;

    public DefaultServerRouter() {
        this.servers = new LinkedHashMap<>();
        this.routing = new LinkedHashMap<>();
    }

    @Override
    public ServerRouter addServer(final String name, final HealthProxyServer server) {
//        final HealthProxyServer existing = servers.get(name);
        servers.put(name, server);
        return this;
    }

    @Override
    public ServerRouter addRouting(final DestinationPattern pattern, final String serverDefinition) {
        if (null == pattern) {
            throw new NullPointerException("destination cannot be null.");
        }
        if (!StringUtils.hasText(serverDefinition)) {
            throw new NullPointerException("serverDefinition cannot be null or empty.");
        }

        final HealthProxyServer server = servers.get(serverDefinition);
        if (null == server) {
            throw new IllegalArgumentException("There is no server with name '" + serverDefinition +
                    "' to apply to destination [" + pattern + "] in the pool of available Servers.  Ensure a " +
                    "server with that destination has first been registered with the addServer method(s).");

        }
        routing.put(pattern, server);
        return this;
    }

    @Override
    public Map<DestinationPattern, HealthProxyServer> getRoutingRules() {
        return routing;
    }
}
package com.github.pangolin.routing.rule;

import com.github.pangolin.routing.upstream.AbstractServer;
import com.github.pangolin.routing.upstream.UpstreamServer;
import com.github.pangolin.routing.upstream.UpstreamServerProvider;
import com.github.pangolin.routing.rule.pattern.DestinationPattern;
import io.netty.channel.ChannelHandler;
import lombok.extern.slf4j.Slf4j;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.Map;

/**
 * @since 20240411
 */
@Slf4j
public class RuleBasedUpstreamServer extends AbstractServer {
    private final RulesProvider rulesProvider;
    private final UpstreamServerProvider upstreamServerProvider;

    public RuleBasedUpstreamServer(final String name, final RulesProvider rulesProvider, final UpstreamServerProvider provider) {
        super(name);
        this.rulesProvider = rulesProvider;
        this.upstreamServerProvider = provider;
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public ChannelHandler newSocketProxyHandler(final InetSocketAddress destination) {
        final UpstreamServer upstreamServer = select(destination);
        return null != upstreamServer ? newProxyHandler(upstreamServer, (InetSocketAddress) destination) : null;
    }

    protected ChannelHandler newProxyHandler(final UpstreamServer server, final InetSocketAddress sa) {
        return server.newSocketProxyHandler(sa);
    }

    private UpstreamServer select(final SocketAddress destinationAddress) {
        if (!(destinationAddress instanceof InetSocketAddress)) {
            log.info("[ROUTING] will bypass the upstream => {}", destinationAddress);
            return null;
        }
        final InetSocketAddress sa = (InetSocketAddress) destinationAddress;
        final Map<DestinationPattern, String> rules = rulesProvider.getRules();
        for (final Map.Entry<DestinationPattern, String> entry : rules.entrySet()) {
            if (!entry.getKey().matches(sa)) {
                continue;
            }

            final UpstreamServer proxyToUse = upstreamServerProvider.getServer(entry.getValue());
            log.info("[ROUTING] will use the upstream '{}' => {}:{}", entry.getValue(), sa.getHostString(), sa.getPort());
            if (null != proxyToUse) {
                return proxyToUse;
            } else {
                log.warn("[ROUTING] NOT FOUND the upstream '{}' => {}:{}", entry.getValue(), sa.getHostString(), sa.getPort());
            }
        }

        log.info("[ROUTING] will bypass the upstream => {}:{}", sa.getHostString(), sa.getPort());
        return null;
    }

}

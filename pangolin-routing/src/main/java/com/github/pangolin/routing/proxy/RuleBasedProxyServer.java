package com.github.pangolin.routing.proxy;

import com.github.pangolin.routing.proxy.ProxyServer;
import com.github.pangolin.routing.proxy.ProxyServerProvider;
import com.github.pangolin.routing.rule.RulesProvider;
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
public class RuleBasedProxyServer implements ProxyServer {
    private final String name;
    private final RulesProvider rulesProvider;
    private final ProxyServerProvider proxyServerProvider;

    public RuleBasedProxyServer(final String name, final RulesProvider rulesProvider, final ProxyServerProvider provider) {
        this.name = name;
        this.rulesProvider = rulesProvider;
        this.proxyServerProvider = provider;
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public ChannelHandler newProxyHandler(final InetSocketAddress sa) {
        final ProxyServer proxyServer = select(sa);
        return null != proxyServer ? newProxyHandler(proxyServer, (InetSocketAddress) sa) : null;
    }

    protected ChannelHandler newProxyHandler(final ProxyServer server, final InetSocketAddress sa) {
        return server.newProxyHandler(sa);
    }

    private ProxyServer select(final SocketAddress destinationAddress) {
        if (!(destinationAddress instanceof InetSocketAddress)) {
            log.info("[ROUTING] will bypass the proxy => {}", destinationAddress);
            return null;
        }
        final InetSocketAddress sa = (InetSocketAddress) destinationAddress;
        final Map<DestinationPattern, String> rules = rulesProvider.getRules();
        for (final Map.Entry<DestinationPattern, String> entry : rules.entrySet()) {
            if (!entry.getKey().matches(sa)) {
                continue;
            }

            final ProxyServer proxyToUse = proxyServerProvider.getInstance(entry.getValue());
            log.info("[ROUTING] will use the proxy '{}' => {}:{}", entry.getValue(), sa.getHostString(), sa.getPort());
            if (null != proxyToUse) {
                return proxyToUse;
            } else {
                log.warn("[ROUTING] NOT FOUND the proxy '{}' => {}:{}", entry.getValue(), sa.getHostString(), sa.getPort());
            }
        }

        log.info("[ROUTING] will bypass the proxy => {}:{}", sa.getHostString(), sa.getPort());
        return null;
    }

}

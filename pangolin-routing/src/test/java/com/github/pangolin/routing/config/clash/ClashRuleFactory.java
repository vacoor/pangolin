package com.github.pangolin.routing.config.clash;

import com.github.pangolin.routing.config.PatternResolver;
import com.github.pangolin.routing.internal.node.HealthChecker;
import com.github.pangolin.routing.internal.node.LoadBalanceProxyServer;
import com.github.pangolin.routing.internal.node.ProxyServer;
import com.github.pangolin.routing.internal.node.spi.ServerResolver;
import com.github.pangolin.routing.pattern.DestinationPattern;
import com.google.common.collect.Maps;
import freework.net.Http;
import io.netty.channel.EventLoopGroup;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;

/**
 */
public class ClashRuleFactory {

    public static Map<DestinationPattern, ProxyServer> parseRules(final List<String> ruleDefinitions, final PatternResolver patternResolver, final ProxyServer udf) {
        final Map<DestinationPattern, ProxyServer> routingRules = Maps.newLinkedHashMap();
        for (final String ruleDefinition : ruleDefinitions) {
            final int i = ruleDefinition.lastIndexOf(",");
            if (-1 < i) {
                final String patternDefinition = ruleDefinition.substring(0, i);
                final DestinationPattern pattern = patternResolver.resolve(patternDefinition);
                if (null != pattern) {
                    final String proxyType = ruleDefinition.substring(i + 1);
                    if ("DIRECT".equals(proxyType)) {
                        routingRules.put(pattern, ProxyServer.DIRECT);
                    } else if ("REJECT".equals(proxyType)) {
                        routingRules.put(pattern, ProxyServer.REJECT);
                    } else {
                        routingRules.put(pattern, udf);
                    }
                }
            }
        }
        return routingRules;
    }

    public static Map<String, ProxyServer> parseProxies(final List<Configuration.ProxyDefinition> proxyDefinitions) {
        final Map<String, ProxyServer> proxies = new HashMap<>();
        for (final Configuration.ProxyDefinition proxyDefinition : proxyDefinitions) {
            final String uri = String.format("%s://%s@%s:%s#%s", proxyDefinition.getType(), urlEncode(proxyDefinition.getPassword()), proxyDefinition.getServer(), proxyDefinition.getPort(), urlEncode(proxyDefinition.getName()));
            final ProxyServer server = resolve(uri);
            proxies.put(server.getName(), server);
        }
        return proxies;
    }

    private static String urlEncode(final String text) {
        return Http.urlEncode(text, StandardCharsets.UTF_8.name());
    }

    private static ProxyServer resolve(final String url) {
        final ServiceLoader<ServerResolver> resolvers = ServiceLoader.load(ServerResolver.class);
        for (final ServerResolver resolver : resolvers) {
            if (!resolver.acceptsUrl(url)) {
                continue;
            }
            final ProxyServer resolved = resolver.resolve(url, null);
            if (null != resolved) {
                return resolved;
            }
        }
        throw new IllegalStateException();
    }

    public static void parseProxyGroups(final List<Configuration.ProxyGroupDefinition> proxyGroupDefinitions, final Map<String, ProxyServer> proxies,
                                 final HealthChecker healthChecker, final EventLoopGroup group) {
        final Map<String, Configuration.ProxyGroupDefinition> proxyGroupDefinitionMap = new HashMap<>();
        for (Configuration.ProxyGroupDefinition proxyGroupDefinition : proxyGroupDefinitions) {
            proxyGroupDefinitionMap.put(proxyGroupDefinition.getName(), proxyGroupDefinition);
        }
        for (Configuration.ProxyGroupDefinition proxyGroupDefinition : proxyGroupDefinitions) {
            parseProxyGroup(proxyGroupDefinition, proxyGroupDefinitionMap, proxies, healthChecker, group);
        }
    }

    private static ProxyServer parseProxyGroup(final Configuration.ProxyGroupDefinition proxyGroupDefinition,
                                               final Map<String, Configuration.ProxyGroupDefinition> proxyGroupDefinitions,
                                               final Map<String, ProxyServer> proxies,
                                               final HealthChecker healthChecker, final EventLoopGroup group) {
        final String name = proxyGroupDefinition.getName();
        final List<String> proxyNames = proxyGroupDefinition.getProxies();
        final List<ProxyServer> proxiesInGroup = new LinkedList<>();
        for (final String proxyName : proxyNames) {
            final ProxyServer dependency = proxies.get(proxyName);
            if (proxyName.equals(name)) {
                continue;
            }
            if (null == dependency) {
                final Configuration.ProxyGroupDefinition dependencyGroupDefinition = proxyGroupDefinitions.get(proxyName);
                if (null == dependencyGroupDefinition) {
                    throw new IllegalStateException("Proxy[Group] not found: " + proxyName);
                }
                final ProxyServer dependencyGroup = parseProxyGroup(dependencyGroupDefinition, proxyGroupDefinitions, proxies, healthChecker, group);
                proxiesInGroup.add(dependencyGroup);
            } else {
                proxiesInGroup.add(dependency);
            }
        }
        final LoadBalanceProxyServer proxyGroup = new LoadBalanceProxyServer(name, healthChecker, proxiesInGroup, group);
        proxies.put(name, proxyGroup);
        return proxyGroup;
    }

}

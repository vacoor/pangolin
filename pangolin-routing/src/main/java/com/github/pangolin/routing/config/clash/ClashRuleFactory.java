package com.github.pangolin.routing.config.clash;

import com.github.pangolin.routing.proxy.ProxyServer;
import com.github.pangolin.routing.config.PatternResolver;
import com.github.pangolin.routing.proxy.spi.ServerResolver;
import com.github.pangolin.routing.proxy.health.HealthChecker;
import com.github.pangolin.routing.rule.pattern.DestinationPattern;
import com.github.pangolin.routing.proxy.LoadBalanceProxyServer;
import freework.net.Http;
import io.netty.channel.EventLoopGroup;
import lombok.extern.slf4j.Slf4j;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 *
 */
@Slf4j
public class ClashRuleFactory {

    public static Map<DestinationPattern, String> parseRules(final URL url, final PatternResolver... resolvers) throws IOException  {
        final Reader reader = new InputStreamReader(url.openStream(), StandardCharsets.UTF_8);
        final Map<DestinationPattern, String> rules = new LinkedHashMap<>();
        final BufferedReader r = reader instanceof BufferedReader ? (BufferedReader) reader : new BufferedReader(reader);
        String line;
        while (null != (line = r.readLine())) {
            final int index = line.indexOf('#');
            final String lineToUse = -1 < index ? line.substring(0, index).trim() : line.trim();
            if (lineToUse.isEmpty()) {
                continue;
            }

            final int i = lineToUse.lastIndexOf(",");
            if (-1 < i) {
                final String patternDefinition = lineToUse.substring(0, i);
                for (PatternResolver resolver : resolvers) {
                    List<DestinationPattern> patterns = resolver.resolve(patternDefinition, url);
                    if (null != patterns) {
                        for (DestinationPattern pattern : patterns) {
                            final String proxyType = lineToUse.substring(i + 1);
                            rules.put(pattern, proxyType);
                        }
                    }
                }
            }
        }
        return rules;
    }

    private static Map<DestinationPattern, String> parseRules(final List<String> ruleDefinitions, final PatternResolver patternResolver, final URL url) throws IOException {
        final Map<DestinationPattern, String> routingRules = new LinkedHashMap();
        for (final String ruleDefinition : ruleDefinitions) {
            final int i = ruleDefinition.lastIndexOf(",");
            if (-1 < i) {
                final String patternDefinition = ruleDefinition.substring(0, i);
                final List<DestinationPattern> patterns = patternResolver.resolve(patternDefinition, url);
                if (null != patterns) {
                    for (DestinationPattern pattern : patterns) {
                        final String proxyType = ruleDefinition.substring(i + 1);
                        routingRules.put(pattern, proxyType);
                    }
                }
            }
        }
        return routingRules;
    }

    public static Map<String, ProxyServer> parseProxies(final List<Configuration.ProxyDefinition> proxyDefinitions) {
        final Map<String, ProxyServer> proxies = new HashMap<>();
        for (final Configuration.ProxyDefinition proxyDefinition : proxyDefinitions) {
            if ("0.0.0.0".equalsIgnoreCase(proxyDefinition.getServer())) {
                continue;
            }
            final String uri = String.format("%s://%s@%s:%s#%s", proxyDefinition.getType(), urlEncode(proxyDefinition.getPassword()), proxyDefinition.getServer(), proxyDefinition.getPort(), urlEncode(proxyDefinition.getName()));
            log.debug("resolve uri: {}", uri);
            final ProxyServer server = resolve(uri);
            log.debug("resolved uri: {}", uri);
            proxies.put(server.getName(), server);
        }
        return proxies;
    }

    private static String urlEncode(final String text) {
        return Http.urlEncode(text, StandardCharsets.UTF_8.name());
    }

    private static final ServiceLoader<ServerResolver> RESOLVERS = ServiceLoader.load(ServerResolver.class);

    private static ProxyServer resolve(final String url) {
        for (final ServerResolver resolver : RESOLVERS) {
            if (!resolver.acceptsUrl(url)) {
                continue;
            }
            final ProxyServer resolved = resolver.resolve(url, new Properties());
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
